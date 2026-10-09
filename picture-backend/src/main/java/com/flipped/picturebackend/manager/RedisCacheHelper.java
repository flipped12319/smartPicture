package com.flipped.picturebackend.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 缓存读写的安全封装
 * <p>
 * 缓存是「可降级」的组件，Redis 挂掉不应该拖慢甚至拖垮业务，因此这里做了两件事：
 * <ol>
 *     <li>读写异常一律在本类内部消化，调用方只会拿到 null（读）或什么都不发生（写），
 *         从而自动降级到数据库；</li>
 *     <li>失败后开启「熔断」，在 {@link #CIRCUIT_OPEN_MILLIS} 毫秒内不再访问 Redis。
 *         否则每个请求都要白白等待一次超时，页面会明显变慢。</li>
 * </ol>
 */
@Slf4j
@Component
public class RedisCacheHelper {

    /**
     * 熔断持续时长：这段时间内直接跳过 Redis，不再等待超时
     */
    private static final long CIRCUIT_OPEN_MILLIS = 30_000L;

    /**
     * 熔断恢复时间点（毫秒时间戳）；0 表示未熔断
     */
    private final AtomicLong unavailableUntil = new AtomicLong(0L);

    /**
     * 版本号在本地缓存的存活时长
     * <p>
     * 版本号本身也存在 Redis 里，若每个请求都去读一次，等于把缓存的意义抵消掉一半，
     * 所以在本地先缓存几秒。代价是：其他实例感知到版本变化最多延迟这么久。
     */
    private static final long VERSION_LOCAL_TTL_MILLIS = 3_000L;

    /**
     * 版本号本地缓存：key = 版本号在 Redis 中的 key，value = 版本值
     */
    private final Cache<String, Long> versionLocalCache = Caffeine.newBuilder()
            .maximumSize(64L)
            .expireAfterWrite(VERSION_LOCAL_TTL_MILLIS, TimeUnit.MILLISECONDS)
            .build();

    /**
     * Redis 命中 / 未命中计数，仅用于观测，不参与任何判断
     */
    private final AtomicLong hitCount = new AtomicLong(0L);

    private final AtomicLong missCount = new AtomicLong(0L);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 读取缓存
     *
     * @return 缓存值；Redis 不可用或未命中时返回 null，由调用方降级到数据库
     */
    public String get(String key) {
        if (isCircuitOpen()) {
            return null;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            markAvailable();
            if (value == null) {
                missCount.incrementAndGet();
            } else {
                hitCount.incrementAndGet();
            }
            return value;
        } catch (Exception e) {
            markUnavailable("读取", e);
            return null;
        }
    }

    /**
     * 写入缓存（尽力而为，失败只记录日志，不影响主流程）
     */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        if (isCircuitOpen()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
            markAvailable();
        } catch (Exception e) {
            markUnavailable("写入", e);
        }
    }

    /**
     * Redis 命中次数（仅用于观测）
     */
    public long getHitCount() {
        return hitCount.get();
    }

    /**
     * Redis 未命中次数（仅用于观测）
     */
    public long getMissCount() {
        return missCount.get();
    }

    /**
     * 当前是否处于熔断状态（即认为 Redis 不可用）
     */
    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < unavailableUntil.get();
    }

    /**
     * 读取缓存版本号
     * <p>
     * 结果会在本地缓存 {@link #VERSION_LOCAL_TTL_MILLIS} 毫秒，避免每个请求都往 Redis 跑一趟。
     * Redis 不可用时返回 0：此时缓存本来也不可用，退化成「本地固定版本」不会有额外影响。
     *
     * @param versionKey 版本号在 Redis 中的 key
     */
    public long getVersion(String versionKey) {
        Long cached = versionLocalCache.getIfPresent(versionKey);
        if (cached != null) {
            return cached;
        }
        if (isCircuitOpen()) {
            return 0L;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(versionKey);
            long version = 0L;
            if (value != null) {
                try {
                    version = Long.parseLong(value.trim());
                } catch (NumberFormatException ignored) {
                    // 值被意外改动时按 0 处理即可，不必打断业务
                }
            }
            versionLocalCache.put(versionKey, version);
            markAvailable();
            return version;
        } catch (Exception e) {
            markUnavailable("读取版本号", e);
            return 0L;
        }
    }

    /**
     * 版本号 +1，让所有基于旧版本号拼出来的缓存 key 立即失效
     * <p>
     * 这是「用一次自增代替按前缀删除」的关键：不需要 SCAN / KEYS 去枚举缓存 key。
     *
     * @param versionKey 版本号在 Redis 中的 key
     */
    public void bumpVersion(String versionKey) {
        // 先让本实例的本地版本失效，避免本机继续用旧版本号命中旧缓存
        versionLocalCache.invalidate(versionKey);
        if (isCircuitOpen()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().increment(versionKey);
            markAvailable();
        } catch (Exception e) {
            markUnavailable("更新版本号", e);
        }
    }

    /**
     * 标记 Redis 不可用并开启熔断窗口
     */
    private void markUnavailable(String action, Exception e) {
        long now = System.currentTimeMillis();
        long until = now + CIRCUIT_OPEN_MILLIS;
        // 只在熔断「首次开启」时打印一次告警，避免并发场景下刷屏
        if (unavailableUntil.getAndSet(until) <= now) {
            log.warn("Redis {} 失败，已降级为「本地缓存 + 数据库」，{} 毫秒内不再访问 Redis。原因: {}",
                    action, CIRCUIT_OPEN_MILLIS, e.getMessage());
        }
    }

    /**
     * 标记 Redis 已恢复，关闭熔断窗口
     */
    private void markAvailable() {
        if (unavailableUntil.getAndSet(0L) > 0L) {
            log.info("Redis 已恢复，重新启用分布式缓存");
        }
    }
}

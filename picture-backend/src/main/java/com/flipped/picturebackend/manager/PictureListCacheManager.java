package com.flipped.picturebackend.manager;

import cn.hutool.json.JSONUtil;
import com.flipped.picturebackend.model.vo.CacheStatsVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 图片列表多级缓存管理器
 * <p>
 * 结构：版本号失效 + 本地 Caffeine（L1）+ Redis（L2）。
 * <p>
 * <b>为什么需要版本号：</b>
 * 缓存 key 由「查询条件的 md5」构成，md5 是单向的，因此图片数据变更后
 * 无法反推出「哪些 key 受影响」，只能 SCAN / KEYS 模糊删除，这在生产环境很危险。
 * 于是改为在 key 中插入一个版本号：任何写操作只要让版本号 +1，
 * 所有旧 key 就都不会再被命中，靠 TTL 自然淘汰即可。
 * <p>
 * 该方案同时解决了三件事：
 * <ol>
 *     <li>不需要枚举 key 就能全量失效；</li>
 *     <li>L1 与 L2 用的是同一个 key 字符串，会一起失效；</li>
 *     <li>版本号存在 Redis 中，多实例部署时可一起失效。</li>
 * </ol>
 */
@Slf4j
@Component
public class PictureListCacheManager {

    /**
     * 公共图库列表（/list/page/vo/cache）的缓存命名空间
     */
    public static final String NAMESPACE_PUBLIC = "picture:listPictureVOByPage";

    /**
     * 管理页列表（/list/page/cache）的缓存命名空间
     */
    public static final String NAMESPACE_ADMIN = "picture:listPictureByPage";

    /**
     * 版本号在 Redis 中的 key
     * <p>
     * 两个命名空间共用同一个版本号：它们展示的是同一张 picture 表，
     * 任何写操作让两者一起失效是最简单也最安全的策略（略有过量，但不会出错）。
     */
    private static final String VERSION_KEY = "picture:list:version";

    /**
     * Redis 侧缓存的基础过期时间
     */
    private static final long REDIS_TTL_MINUTES = 5L;

    /**
     * Redis 过期时间的随机抖动上限（秒）
     * <p>
     * 不带抖动的话，同一批写入的 key（例如缓存刚冷启动时被并发请求一起建出来的一批）
     * 会在同一时刻集体过期，形成一次周期性的回源高峰（缓存雪崩）。
     */
    private static final int REDIS_TTL_JITTER_SECONDS = 30;

    /**
     * L1 本地缓存过期时间
     * <p>
     * 刻意远小于 Redis 的 5 分钟：L1 只作为热点缓冲，
     * 这样即使是从 Redis 回填进来的数据，也不会长时间停留在本地不再刷新。
     */
    private static final Duration LOCAL_TTL = Duration.ofSeconds(30);

    /**
     * L1 本地缓存
     */
    private final Cache<String, Optional<String>> localCache = Caffeine.newBuilder()
            .initialCapacity(1024)
            .maximumSize(10000L)
            .expireAfterWrite(LOCAL_TTL)
            // 采集命中率等指标，便于判断 TTL 是否合理
            .recordStats()
            .build();

    @Resource
    private RedisCacheHelper redisCacheHelper;

    /**
     * 拼出带版本号的缓存 key
     *
     * @param namespace      缓存命名空间，见 {@link #NAMESPACE_PUBLIC}
     * @param queryCondition 查询条件对象，序列化后取 md5 作为后缀
     */
    public String buildKey(String namespace, Object queryCondition) {
        String condition = JSONUtil.toJsonStr(queryCondition);
        String hashKey = DigestUtils.md5DigestAsHex(condition.getBytes());
        return namespace + ":v" + redisCacheHelper.getVersion(VERSION_KEY) + ":" + hashKey;
    }

    /**
     * 读取缓存；未命中时通过 dbLoader 回源，并把结果写回两级缓存
     * <p>
     * 回源动作放在 Caffeine 的加载回调里执行：同一个 key 的并发请求只会有一个线程真正调用
     * dbLoader，其余线程等待它的结果，从而避免热点 key 失效瞬间大量请求同时打到数据库（缓存击穿）。
     *
     * @param key      缓存 key
     * @param dbLoader 回源数据库的逻辑，需要返回序列化后的分页结果
     * @return 缓存内容
     */
    public String getOrLoad(String key, Supplier<String> dbLoader) {
        return localCache.get(key, cacheKey -> loadFromRemoteOrDb(cacheKey, dbLoader)).orElse(null);
    }

    /**
     * 回源：先看 Redis（顺带完成 L2 → L1 的回填），再查数据库并写回 Redis
     */
    private Optional<String> loadFromRemoteOrDb(String key, Supplier<String> dbLoader) {
        String remoteValue = redisCacheHelper.get(key);
        if (remoteValue != null) {
            return Optional.of(remoteValue);
        }
        String dbValue = dbLoader.get();
        if (dbValue == null) {
            // 分页结果序列化后至少是个空对象，正常不会走到这里；
            // Caffeine 不接受 null，用 empty 表示「该 key 这次没有可缓存的内容」
            return Optional.empty();
        }
        redisCacheHelper.set(key, dbValue, randomRedisTtlSeconds(), TimeUnit.SECONDS);
        return Optional.of(dbValue);
    }

    /**
     * 生成带随机抖动的 Redis 过期时间（秒）
     */
    private long randomRedisTtlSeconds() {
        return REDIS_TTL_MINUTES * 60
                + ThreadLocalRandom.current().nextInt(REDIS_TTL_JITTER_SECONDS + 1);
    }

    /**
     * 图片数据发生变更时调用：让所有列表缓存立即失效
     * <p>
     * 失效通过「版本号 +1」实现 —— 旧 key 不会再被任何请求命中，靠 TTL 自然淘汰，
     * 因此不需要按前缀删除，也就用不到 SCAN / KEYS 这类高危操作。
     */
    public void invalidateAll() {
        redisCacheHelper.bumpVersion(VERSION_KEY);
        // 本实例的本地缓存直接清空，立即释放内存（其他实例靠版本号变化自然失效）
        localCache.invalidateAll();
        log.info("图片列表缓存已全量失效");
    }

    /**
     * 汇总缓存的运行指标，用于观察命中率、判断 TTL 是否合理
     */
    public CacheStatsVO getStats() {
        CacheStats stats = localCache.stats();
        CacheStatsVO statsVO = new CacheStatsVO();
        // 版本号每次写操作都会 +1，可以用它确认失效机制有没有生效
        statsVO.setVersion(redisCacheHelper.getVersion(VERSION_KEY));
        statsVO.setLocalHitCount(stats.hitCount());
        statsVO.setLocalMissCount(stats.missCount());
        statsVO.setLocalHitRate(stats.hitRate());
        statsVO.setLocalLoadCount(stats.loadCount());
        statsVO.setLocalEvictionCount(stats.evictionCount());
        statsVO.setLocalSize(localCache.estimatedSize());
        statsVO.setLocalTtlSeconds(LOCAL_TTL.getSeconds());
        statsVO.setRedisTtlSeconds(REDIS_TTL_MINUTES * 60);
        statsVO.setRedisTtlJitterSeconds((long) REDIS_TTL_JITTER_SECONDS);
        statsVO.setRedisHitCount(redisCacheHelper.getHitCount());
        statsVO.setRedisMissCount(redisCacheHelper.getMissCount());
        statsVO.setRedisAvailable(!redisCacheHelper.isCircuitOpen());
        return statsVO;
    }
}

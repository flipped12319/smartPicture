package com.flipped.picturebackend.manager;

import com.flipped.picturebackend.model.entity.Space;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空间本地缓存（阶段 4b）
 * <p>
 * 为什么需要它：阶段 4b 之后 {@code spaceService.getById} 与「用户在某空间的角色」都会
 * 变成跨服务调用，而它们**都在图片热路径上** —— 每次图片列表 / 上传 / 删除 / 编辑都会问一次
 * （{@code checkPictureAuth} / {@code checkPictureViewAuth} / 上传前的额度校验）。
 * 不缓存就等于给所有这些接口各加一次网络往返。
 * <p>
 * 缓存两样东西：
 * <ul>
 *     <li><b>空间对象</b>（空间 id → Space）：额度校验与鉴权都要用；</li>
 *     <li><b>成员角色</b>（空间 id + 用户 id → 角色值）：这一个是真正常用的，
 *         图片鉴权每跳过一次就等于省一次远程调用。</li>
 * </ul>
 * <p>
 * <b>代价（必须写在明面上）</b>：成员被移除 / 角色被调低后，本实例最多还会按旧角色放行
 * {@link #ROLE_CACHE_TTL} 这么久。这是「热路径不加网络往返」换来的，与阶段 3b 给用户信息
 * 加 5 分钟缓存是同一类取舍；要彻底消除就得在服务间做缓存失效广播（阶段 6 的
 * {@code picture.changed} fanout 正好是为此准备的）。
 * <p>
 * 刻意不做「写后主动失效」的跨服务回调：单体已经不再持有 space_user，成员变更发生在
 * space-service 侧（前端经网关直接打它），让 space-service 反调单体各实例去清缓存
 * 属于把一个简单问题复杂化。TTL 是这里正确的默认值。
 */
@Component
@Slf4j
public class SpaceCacheManager {

    /**
     * 空间对象缓存：写后 60 秒过期。
     * 空间本身的改动（改名、升级额度）由管理端低频操作，60 秒的不一致可以接受。
     */
    private static final Duration SPACE_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * 成员角色缓存：写后 60 秒过期。
     * 这是**权限相关**的缓存，TTL 刻意取得比用户信息（5 分钟）短得多。
     */
    private static final Duration ROLE_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * 角色缓存命中统计的日志间隔，避免刷屏
     */
    private final Cache<Long, Space> spaceCache = Caffeine.newBuilder()
            .maximumSize(10_000L)
            .expireAfterWrite(SPACE_CACHE_TTL)
            .build();

    /**
     * key = spaceId * 1_000_003 + userId 的字符串形式。
     * 用字符串而不是自定义复合 key，是为了让 key 在日志里可读、也便于按空间前缀批量失效。
     */
    private final Cache<String, Integer> roleCache = Caffeine.newBuilder()
            .maximumSize(50_000L)
            .expireAfterWrite(ROLE_CACHE_TTL)
            .build();

    /**
     * spaceId → 该空间下已缓存的 userId 集合，用于「按空间」精准清除角色缓存
     * （Caffeine 不支持按前缀删除，只能自己记一份索引）
     */
    private final Map<Long, Set<Long>> roleIndex = new ConcurrentHashMap<>();

    // ==================== 空间对象 ====================

    public Space getSpace(Long spaceId) {
        return spaceId == null ? null : spaceCache.getIfPresent(spaceId);
    }

    public void putSpace(Space space) {
        if (space != null && space.getId() != null) {
            spaceCache.put(space.getId(), space);
        }
    }

    /**
     * 空间数据变更后清掉本地缓存（写路径必须调，否则会读到旧额度）
     */
    public void evictSpace(Long spaceId) {
        if (spaceId != null) {
            spaceCache.invalidate(spaceId);
            evictRoles(spaceId);
        }
    }

    // ==================== 成员角色 ====================

    public boolean hasRole(Long spaceId, Long userId) {
        return spaceId != null && userId != null && roleCache.getIfPresent(roleKey(spaceId, userId)) != null;
    }

    /**
     * @return 角色值（0-3）；未缓存或不是成员时返回 null
     */
    public Integer getRole(Long spaceId, Long userId) {
        if (spaceId == null || userId == null) {
            return null;
        }
        return roleCache.getIfPresent(roleKey(spaceId, userId));
    }

    public void putRole(Long spaceId, Long userId, Integer role) {
        if (spaceId == null || userId == null) {
            return;
        }
        roleCache.put(roleKey(spaceId, userId), role);
        roleIndex.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    /**
     * 清除某个空间下所有用户的角色缓存（成员被增删/改角色后调用）。
     * 这里用的是 space-service 侧的写操作，因此实际调用点只有「单体自己写空间」的那些路径。
     */
    public void evictRoles(Long spaceId) {
        if (spaceId == null) {
            return;
        }
        Set<Long> userIds = roleIndex.remove(spaceId);
        if (userIds == null) {
            return;
        }
        userIds.forEach(userId -> roleCache.invalidate(roleKey(spaceId, userId)));
    }

    /**
     * 整体失效（删除空间等场景）
     */
    public void evictAll() {
        spaceCache.invalidateAll();
        roleCache.invalidateAll();
        roleIndex.clear();
    }

    private String roleKey(Long spaceId, Long userId) {
        return spaceId + ":" + userId;
    }

    /**
     * 仅用于诊断日志
     */
    public String stats() {
        return "spaceCache=" + spaceCache.estimatedSize()
                + ", roleCache=" + roleCache.estimatedSize()
                + ", indexedSpaces=" + roleIndex.size();
    }

    /**
     * 便于测试/排查：当前被索引的空间 id
     */
    public Set<Long> indexedSpaceIds() {
        return Collections.unmodifiableSet(roleIndex.keySet());
    }
}

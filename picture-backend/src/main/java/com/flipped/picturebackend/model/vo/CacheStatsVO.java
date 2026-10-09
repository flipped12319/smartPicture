package com.flipped.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片列表缓存运行指标（仅供观测，不参与业务逻辑）
 * <p>
 * 用来回答几个调优问题：命中率够不够、TTL 是不是太长/太短、版本号有没有在涨。
 */
@Data
public class CacheStatsVO implements Serializable {

    /**
     * 当前缓存版本号：每次图片写操作都会 +1，可用它确认失效机制是否生效
     */
    private Long version;

    /**
     * L1（本地 Caffeine）命中次数
     */
    private Long localHitCount;

    /**
     * L1 未命中次数
     */
    private Long localMissCount;

    /**
     * L1 命中率，取值 0 ~ 1
     */
    private Double localHitRate;

    /**
     * L1 回源加载次数，即「真正去查 Redis 或数据库」的次数
     */
    private Long localLoadCount;

    /**
     * L1 因过期或容量上限被淘汰的条目数
     */
    private Long localEvictionCount;

    /**
     * L1 当前条目数（估算值）
     */
    private Long localSize;

    /**
     * L1 过期时间（秒）
     */
    private Long localTtlSeconds;

    /**
     * L2（Redis）基础过期时间（秒）
     */
    private Long redisTtlSeconds;

    /**
     * L2 过期时间的随机抖动上限（秒）
     */
    private Long redisTtlJitterSeconds;

    /**
     * L2 命中次数
     */
    private Long redisHitCount;

    /**
     * L2 未命中次数
     */
    private Long redisMissCount;

    /**
     * Redis 当前是否可用（未熔断）
     */
    private Boolean redisAvailable;

    private static final long serialVersionUID = 1L;
}

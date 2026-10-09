package com.flipped.spaceservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.model.dto.space.QuotaReserveResult;
import com.flipped.spaceservice.model.dto.space.SpaceAddRequest;
import com.flipped.spaceservice.model.dto.space.SpaceQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 空间服务（space 表的唯一属主）。
 * <p>
 * 所有方法都以 **loginUserId** 入参，而不是整个 User 对象：
 * 本服务拿到的只有 userId（JWT 里带的），用户昵称之类的信息按需向 user-service 要。
 * 这样也顺带避免了「把一个残缺的 User 对象在服务之间传来传去」。
 */
public interface SpaceService {

    /**
     * 创建空间
     *
     * @return 新空间 id
     */
    long addSpace(SpaceAddRequest spaceAddRequest, Long loginUserId);

    /**
     * 更新空间（仅管理员可用，管理端接口）
     */
    boolean updateSpace(SpaceUpdateRequest spaceUpdateRequest);

    /**
     * 删除空间（仅本人或管理员）；同时清理空间成员记录
     */
    boolean deleteSpace(Long spaceId, Long loginUserId);

    /**
     * 校验空间
     *
     * @param add 是否为创建时校验
     */
    void validSpace(Space space, boolean add);

    /**
     * 根据空间级别填充限额（最大容量 / 最大数量）
     */
    void fillSpaceBySpaceLevel(Space space);

    /**
     * 校验空间权限：仅创建者本人或平台管理员可编辑/删除
     * <p>
     * 注意这里**只做「空间归属」校验**（创建者 / 平台管理员）。
     * 「团队成员角色」校验在 {@link SpaceUserService#checkSpaceUserAuth} ——
     * 那条规则要查 space_user 表，属于成员服务的职责。
     */
    void checkSpaceAuth(Long loginUserId, Space space);

    /**
     * 分页查询「与我有关」的空间：我创建的 + 我加入的
     */
    Page<Space> listMySpaceByPage(long current, long size, Long loginUserId);

    /**
     * 分页查询空间列表（管理端）
     */
    Page<Space> listSpaceByPage(SpaceQueryRequest queryRequest);

    /**
     * 根据用户 id 查询其**私有空间** id；没有私有空间时返回 null
     */
    Long getSpaceIdByUserId(Long userId);

    /**
     * 按 id 查空间（不存在返回 null）
     */
    Space getById(Long spaceId);

    /**
     * 批量按 id 查空间
     * <p>
     * 参数类型必须写成 {@code Collection<? extends Serializable>} 才能覆盖
     * MyBatis-Plus {@code IRepository#listByIds} 的签名（用 {@code Collection<Long>}
     * 会被判为「名称冲突但方法签名不同」而编译失败）。
     */
    List<Space> listByIds(Collection<? extends Serializable> spaceIds);

    /**
     * 读取空间配额快照
     */
    Space getQuota(Long spaceId);

    /**
     * 按增量调整空间配额，**在服务端一次性原子完成**，并返回调整后的空间。
     * <p>
     * 这是阶段 4 唯一的跨服务写：单体上传/删除图片时不再自己 <code>setSql("totalSize = totalSize + Δ")</code>，
     * 而是调本方法。要点：
     * <ul>
     *     <li>Δ 由调用方计算，本服务只做「累加 + 边界保护」，不信任调用方传入的绝对值；</li>
     *     <li>{@code totalSize}/{@code totalCount} 在 SQL 里带 {@code GREATEST(...,0)} 兜底，
     *         避免并发删除把统计值减成负数（原实现没有这一层保护）；</li>
     *     <li>返回调整后的**最新**空间对象，调用方可以直接刷新自己的本地缓存，
     *         不必再发一次查询。</li>
     * </ul>
     *
     * @param sizeDelta  容量增量（可为负）
     * @param countDelta 数量增量（可为负）
     * @param reason     仅用于日志，便于对账时定位是哪条业务链路造成的偏差
     * @return 调整后的空间；空间不存在时抛 40400
     */
    Space applyQuotaDelta(Long spaceId, long sizeDelta, long countDelta, String reason);

    /**
     * 原子「校验 + 预占」额度（阶段 5d-b）。
     * <p>
     * 与 {@link #applyQuotaDelta} 的区别：<b>它会因额度不足而拒绝</b>，而且校验与扣减
     * 由一条带 WHERE 条件的 UPDATE 完成，并发下不会超卖。
     * <p>
     * 调用时机：单体在**落库图片之前**调用它把额度占住；图片入库失败时要调用
     * {@link #applyQuotaDelta} 把预占还回去（负数增量）。
     *
     * @param sizeDelta  容量增量（占用时为正）
     * @param countDelta 数量增量（占用时为正）
     * @param reason     仅用于日志
     * @return 预占结果：成功带最新空间快照；额度不足时 {@code limited} 非空
     *         （**不是异常** —— 「额度不足」是业务判定，「服务不可用」才是故障）
     */
    QuotaReserveResult reserveQuota(Long spaceId, long sizeDelta, long countDelta, String reason);
}

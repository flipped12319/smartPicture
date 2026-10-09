package com.flipped.picturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.vo.SpaceVO;

import java.util.Collection;
import java.util.List;

/**
 * 空间服务（单体侧）
 * <p>
 * 阶段 4b：单体**不再读写 space 表**（该表已归 picture-space-service 所有），
 * 实现换成「本地 Caffeine 缓存 + OpenFeign」，原有调用点（{@code getById} /
 * {@code checkSpaceAuth} / 配额更新 …）都不改签名。
 * <p>
 * 与阶段 3b 的用户服务保持同一条底线：**接口不变、实现换掉**，风险集中在实现里。
 * <p>
 * 这个接口现在只保留**单体真正还需要**的能力 —— 图片/相册链路要用到的空间查询、
 * 归属校验、额度更新。空间的增删改与列表查询已由 space-service 直接对前端提供
 * （网关把 {@code /api/space/**} 路由过去），单体不再需要一层纯转发的空壳，
 * 否则就是典型的分布式单体。因此以下方法被刻意移除：
 * <ul>
 *     <li>{@code getQueryWrapper} / {@code lambdaUpdate} —— MyBatis-Plus 专属签名，
 *         跨服务之后没有意义（配额更新换成 {@link #updateSpaceQuota}）；</li>
 *     <li>{@code addSpace} / {@code updateSpace} / {@code removeById} —— 只被管理端
 *         {@code SpaceController} 使用，而该 Controller 在 4b 已删除；</li>
 *     <li>{@code page} / {@code getSpaceVO} —— 前者只服务于被删除的管理端列表，
 *         后者没有任何调用方。{@link #getSpaceVOPage} 保留，因为「我的空间」列表仍要用它渲染。</li>
 * </ul>
 */
public interface SpaceService {

    /**
     * 获取空间包装类（分页）：填充创建者信息与当前用户在各空间中的角色
     */
    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, Long loginUserId);

    /**
     * 校验空间权限：仅创建者本人或平台管理员可编辑/删除
     */
    void checkSpaceAuth(Long loginUserId, Space space);

    /**
     * 根据用户 id 查询其私有空间 id；没有则返回 null
     */
    Long getSpaceIdByUserId(Long userId);

    /**
     * 按 id 查空间（走本地缓存 + space-service）；不存在返回 null
     */
    Space getById(Long id);

    /**
     * 批量查空间（先命中本地缓存，未命中的一次远程批量查询）
     */
    List<Space> listByIds(Collection<Long> ids);

    /**
     * 按增量调整空间配额。
     * <p>
     * 阶段 4 的**跨服务写**：原来是在单体的本地事务里
     * {@code setSql("totalSize = totalSize + Δ")}，现在改为调 space-service 的内部接口。
     * 语义上的变化必须在调用点心里有数（避免静默的数据不一致）：
     * <ol>
     *     <li>空间的**存在性**由本方法校验（空间不存在 → 40400），不再由调用方自己保证；</li>
     *     <li>容量与数量是**一次远程调用里原子完成的**（space-service 侧一条 SQL），
     *         但它**不在单体的事务里** —— 跨进程，本地事务管不到它；</li>
     *     <li>失败直接抛异常（不返回 false），让调用方的事务回滚：
     *         「配额没扣成功却把图片写进去了」比「整单失败」糟糕得多。</li>
     * </ol>
     * 极端情况下仍会出现「配额扣减成功、单体事务回滚」的偏差 —— 这正是阶段 4c 要改成
     * 事件驱动 + 每日对账的原因（见方案 §4.6）。
     *
     * @param reason 仅用于日志与对账，便于定位是哪条业务链路造成的偏差
     */
    void updateSpaceQuota(Long spaceId, long sizeDelta, long countDelta, String reason);

    /**
     * **同步**改额度，仅用于补偿/归还（阶段 5d-b 新增）。
     * <p>
     * 与 {@link #updateSpaceQuota} 的区别：它是直接 Feign 调用，不经过本地消息表。
     * 补偿必须立即可见 —— 用户刚被拒绝、马上会再试一次，若额度异步归还，
     * 他会看到「额度不足」却查不出原因。
     *
     * @throws com.flipped.picturebackend.exception.BusinessException 空间服务不可用时抛 50000
     */
    void updateSpaceQuotaNow(Long spaceId, long sizeDelta, long countDelta, String reason);

    /**
     * 原子「校验 + 预占」额度（阶段 5d-b）：上传前把额度占住。
     *
     * <p><b>为什么上传必须走这个方法，而不是先查再扣</b>：
     * 改动前的做法是「上传前查一次额度 → 事务内扣一次」，中间夹着一次 COS 上传（几百毫秒），
     * 两个并发上传可以**都通过检查**，最后一起超限（TOCTOU）。
     * 这里把校验与扣减合并成 space-service 侧的一条条件 UPDATE，由数据库保证原子性。
     *
     * <p>与 {@link #updateSpaceQuota} 的分工是刻意的：
     * <ul>
     *     <li>本方法用于**占用**，会因额度不足而失败（业务判定，抛 40000 带具体原因）；</li>
     *     <li>{@code updateSpaceQuota} 用于**归还/补偿**，永不因超限失败 ——
     *         删除必须永远能执行，否则限额配错时用户被永久卡死。</li>
     * </ul>
     *
     * @return 预占后的空间快照（便于调用方刷新缓存）
     */
    Space reserveSpaceQuota(Long spaceId, long sizeDelta, long countDelta, String reason);
}

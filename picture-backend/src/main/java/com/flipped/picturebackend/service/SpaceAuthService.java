package com.flipped.picturebackend.service;

import com.flipped.picturebackend.model.entity.Space;

/**
 * 空间访问能力（阶段 4b）
 * <p>
 * 单体不再持有 space / space_user 表，空间与成员的读写全部经 OpenFeign 走 space-service。
 * 把「查空间」「查角色」单独抽出来（而不是塞进 SpaceService / SpaceUserService 的实现里）
 * 是因为它们的**失败策略与用途有关**：鉴权路径必须明确报错，渲染路径可以降级。
 * <p>
 * <b>两种「查不到」必须分开</b>（阶段 4 实测踩过一次，见
 * {@link #getSpaceForAuth} 的注释）：
 * <ul>
 *     <li><b>空间真的不存在</b>：space-service 对不存在的 id 返回 {@code code=0, data=null}，
 *         这是确定的「没有」；</li>
 *     <li><b>space-service 不可用</b>：抛异常或返回非 0 —— 这只是「不知道」，
 *         绝不能报成「空间不存在」，否则用户以为空间被删了，排查方向全错。</li>
 * </ul>
 */
public interface SpaceAuthService {

    /**
     * 查空间，供**鉴权/写路径**使用（图片列表、上传、编辑、删除、相册都要它）。
     * <p>
     * 返回值语义：
     * <ul>
     *     <li>返回 Space —— 空间存在；</li>
     *     <li>返回 {@code null} —— 空间**确实不存在**（可以放心报 40400「空间不存在」）；</li>
     *     <li>抛 50000「空间服务暂不可用」—— 依赖故障（**不会**伪装成「空间不存在」）。</li>
     * </ul>
     * 实现上也不吞异常：让故障以异常的形式暴露出来，比让调用方拿到一个假的 null 好得多。
     */
    Space getSpaceForAuth(Long spaceId);

    /**
     * 校验用户在指定空间中是否至少具备指定角色。
     * <p>
     * 入参是 {@link Space} 而不是 spaceId：调用点（图片鉴权等热路径）本来就已经把
     * Space 对象查出来了，只传 id 会丢掉一次免费的存在性判断。
     * <p>
     * 失败语义：
     * <ul>
     *     <li>space 为 null → 40400（与拆分前一致）；</li>
     *     <li>用户不是成员 / 角色不够 → 40101（带「需要某某权限」的提示）；</li>
     *     <li>space-service 不可用 → 50000「空间服务暂不可用」（**绝不静默放行**）。</li>
     * </ul>
     *
     * @param requireRole 为 null 时按 VIEWER 处理（调用方漏传参数不该变成「不校验」）
     */
    void checkSpaceUserAuth(Space space, Long loginUserId, Integer requireRole);

    /**
     * 解析用户在某空间中的角色；不是成员返回 null，「渲染路径」不抛异常。
     * 依赖不可用时同样返回 null，因此**不要**用它做鉴权判断。
     */
    Integer getRole(Long spaceId, Long userId);

    /**
     * 空间数据变更后清本地缓存（额度变更、删除空间等写路径必须调）
     */
    void evictSpaceCache(Long spaceId);
}

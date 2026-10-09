package com.flipped.picturebackend.service;

import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;

import java.util.List;
import java.util.Map;

/**
 * 空间成员服务（单体侧）
 * <p>
 * 阶段 4b：单体**不再读写 space_user 表**（该表已归 picture-space-service 所有）。
 * <p>
 * 这里刻意只剩「角色查询 + 权限校验」，因为它们是**图片热路径上真正需要**的能力
 * （每次图片列表 / 上传 / 编辑 / 删除 / 相册操作都要判定用户在该空间的角色）。
 * 邀请、接受邀请、修改角色、成员列表这些是**前端经网关直接打 space-service** 的，
 * 单体侧的 {@code SpaceUserController} 在 4b 已删除 —— 保留一层纯转发的空壳
 * 只会让人以为单体还在参与成员管理。
 * <p>
 * 与阶段 3b 的用户服务同样的底线：**接口语义不变、实现换掉**。
 * 参数刻意保留完整的 {@code User loginUser}（而不是只传 userId）：调用点大多直接从
 * 请求里取登录用户，而「没登录」必须报 40100 而不是 500。如果签名只要 {@code Long}，
 * 调用点就会先写 {@code loginUser.getId()} —— 未登录时那是 NPE，最终被兜底成
 * 「系统错误」，把「你没登录」这个明确结论变成一个查不出来的故障。
 */
public interface SpaceUserService {

    /**
     * 批量获取用户在一组空间中的角色，用于列表场景，避免逐个空间查询
     *
     * @return spaceId -> 角色；不是成员的空间不会出现在返回值里
     */
    Map<Long, SpaceUserRoleEnum> getSpaceRoleMap(List<Long> spaceIds, Long userId);

    /**
     * 校验用户在指定空间中是否至少具备指定角色。
     * <p>
     * 团队空间权限校验的**唯一入口**：图片上传/编辑/删除、相册、文件下载都调它。
     * 失败语义（与拆分前保持一致）：
     * <ul>
     *     <li>未登录（loginUser 为 null）→ 40100；</li>
     *     <li>space 为 null → 40400；</li>
     *     <li>不是成员 / 角色不够 → 40101；</li>
     *     <li>space-service 不可用 → 50000（**绝不静默放行**）。</li>
     * </ul>
     */
    void checkSpaceUserAuth(Space space, User loginUser, SpaceUserRoleEnum requireRole);
}

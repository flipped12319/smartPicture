package com.flipped.spaceservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.model.dto.space.SpaceUserInviteRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserRoleUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.model.vo.SpaceUserVO;

import java.util.List;
import java.util.Map;

/**
 * 空间成员服务（space_user 表的唯一属主）。
 * <p>
 * 该服务同时负责「团队成员管理」与「邀请流程」，并对外提供统一的团队空间权限校验入口。
 */
public interface SpaceUserService {

    /**
     * 邀请用户加入团队空间（空间管理员操作）
     *
     * @return 空间成员记录 id
     */
    long inviteUser(SpaceUserInviteRequest inviteRequest, Long loginUserId);

    /**
     * 处理收到的邀请
     *
     * @param id    空间成员记录 id
     * @param agree true-接受 false-拒绝
     */
    void handleInvitation(long id, boolean agree, Long loginUserId);

    /**
     * 修改成员的空间角色（空间管理员操作）
     */
    void updateMemberRole(SpaceUserRoleUpdateRequest updateRequest, Long loginUserId);

    /**
     * 移除成员：空间管理员可移除任意普通成员，成员也可主动退出空间
     */
    void removeMember(long id, Long loginUserId);

    /**
     * 分页查询空间成员列表（仅已加入的成员）
     */
    Page<SpaceUserVO> listSpaceUserByPage(SpaceUserQueryRequest queryRequest, Long loginUserId);

    /**
     * 分页查询我收到的、尚未处理的邀请
     */
    Page<SpaceUserVO> listMyInvitationByPage(SpaceUserQueryRequest queryRequest, Long loginUserId);

    /**
     * 获取用户在指定空间中的角色；不是该空间成员时返回 null
     */
    SpaceUserRoleEnum getRoleInSpace(Space space, Long userId);

    /**
     * 批量获取用户在一组空间中的角色，用于列表场景，避免逐个空间查询
     *
     * @return spaceId -> 角色；不是成员的空间不会出现在返回值里
     */
    Map<Long, SpaceUserRoleEnum> getRoleMap(List<Long> spaceIds, Long userId);

    /**
     * 校验用户在指定空间中是否至少具备指定角色。
     * <p>
     * 这是**团队空间权限的唯一入口**：单体拆分前后、单体内部（图片上传/编辑/删除、
     * 相册、文件下载）都调它。放在成员服务里是因为它要查 space_user 表。
     *
     * @param requireRole 为 null 时按最低的 VIEWER 处理（调用方漏传参数不该变成「不校验」）
     */
    void checkSpaceUserAuth(Space space, Long loginUserId, SpaceUserRoleEnum requireRole);
}

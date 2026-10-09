package com.flipped.spaceservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.common.BaseResponse;
import com.flipped.spaceservice.common.ResultUtils;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.exception.ThrowUtils;
import com.flipped.spaceservice.model.dto.space.SpaceUserHandleRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserInviteRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserRoleUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.vo.SpaceUserVO;
import com.flipped.spaceservice.model.vo.SpaceVO;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.service.SpaceService;
import com.flipped.spaceservice.service.SpaceUserService;
import com.flipped.spaceservice.service.SpaceViewAssembler;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 团队空间成员接口
 * <p>
 * 团队成员分为四档：0-只能看、1-能看能上传、2-能看能上传还能编辑、3-管理员（空间创建者）。
 * 邀请使用「邀请中」的成员记录表达，被邀请人接受后才正式成为成员。
 * <p>
 * 路径与单体原来的完全一致（{@code /spaceUser/...}），前端与网关契约不变。
 */
@RestController
@RequestMapping("/spaceUser")
public class SpaceUserController {

    @Resource
    private SpaceUserService spaceUserService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceViewAssembler spaceViewAssembler;

    @Resource
    private AuthService authService;

    private Long requireLoginUserId(String authorization) {
        return authService.resolveLoginUserId(authorization);
    }

    /**
     * 邀请用户加入团队空间（仅空间管理员可用）
     *
     * @return 空间成员记录 id
     */
    @PostMapping("/invite")
    public BaseResponse<Long> inviteUser(@RequestBody SpaceUserInviteRequest inviteRequest,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(inviteRequest == null, ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        return ResultUtils.success(spaceUserService.inviteUser(inviteRequest, loginUserId));
    }

    /**
     * 接受邀请
     */
    @PostMapping("/accept")
    public BaseResponse<Boolean> acceptInvitation(@RequestBody SpaceUserHandleRequest handleRequest,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(handleRequest == null || handleRequest.getId() == null || handleRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        spaceUserService.handleInvitation(handleRequest.getId(), true, loginUserId);
        return ResultUtils.success(true);
    }

    /**
     * 拒绝邀请
     */
    @PostMapping("/reject")
    public BaseResponse<Boolean> rejectInvitation(@RequestBody SpaceUserHandleRequest handleRequest,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(handleRequest == null || handleRequest.getId() == null || handleRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        spaceUserService.handleInvitation(handleRequest.getId(), false, loginUserId);
        return ResultUtils.success(true);
    }

    /**
     * 修改成员的空间权限（仅空间管理员可用）
     */
    @PostMapping("/update/role")
    public BaseResponse<Boolean> updateMemberRole(@RequestBody SpaceUserRoleUpdateRequest updateRequest,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        spaceUserService.updateMemberRole(updateRequest, loginUserId);
        return ResultUtils.success(true);
    }

    /**
     * 移除成员（空间管理员移除他人）或 主动退出空间（成员操作自己）
     */
    @PostMapping("/remove")
    public BaseResponse<Boolean> removeMember(@RequestBody SpaceUserHandleRequest handleRequest,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(handleRequest == null || handleRequest.getId() == null || handleRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        spaceUserService.removeMember(handleRequest.getId(), loginUserId);
        return ResultUtils.success(true);
    }

    /**
     * 分页查询空间成员列表（需要是该空间的成员）
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<SpaceUserVO>> listSpaceUserByPage(@RequestBody SpaceUserQueryRequest queryRequest,
                                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        return ResultUtils.success(spaceUserService.listSpaceUserByPage(queryRequest, loginUserId));
    }

    /**
     * 分页查询我收到的、还未处理的邀请
     */
    @PostMapping("/my/invitation/list/page")
    public BaseResponse<Page<SpaceUserVO>> listMyInvitation(
            @RequestBody(required = false) SpaceUserQueryRequest queryRequest,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long loginUserId = requireLoginUserId(authorization);
        return ResultUtils.success(spaceUserService.listMyInvitationByPage(queryRequest, loginUserId));
    }

    /**
     * 分页查询「与我有关」的空间：我创建的 + 我加入的
     * <p>
     * 每条记录都带 currentUserRole，前端据此过滤出「可上传」的空间。
     */
    @PostMapping("/my/space/list/page")
    public BaseResponse<Page<SpaceVO>> listMySpaceByPage(
            @RequestBody(required = false) SpaceUserQueryRequest queryRequest,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long loginUserId = requireLoginUserId(authorization);
        long current = queryRequest == null ? 1 : queryRequest.getCurrent();
        long size = queryRequest == null ? 12 : queryRequest.getPageSize();
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 条");
        Page<Space> spacePage = spaceService.listMySpaceByPage(current, size, loginUserId);
        return ResultUtils.success(spaceViewAssembler.toVOPage(spacePage, loginUserId));
    }
}

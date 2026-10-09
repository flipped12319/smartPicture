package com.flipped.spaceservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.exception.ThrowUtils;
import com.flipped.spaceservice.feign.UserClient;
import com.flipped.spaceservice.mapper.SpaceUserMapper;
import com.flipped.spaceservice.model.dto.space.SpaceUserInviteRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUserRoleUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.entity.SpaceUser;
import com.flipped.spaceservice.model.enums.SpaceTypeEnum;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.model.enums.SpaceUserStatusEnum;
import com.flipped.spaceservice.model.vo.SpaceUserVO;
import com.flipped.spaceservice.model.vo.SpaceVO;
import com.flipped.spaceservice.model.vo.UserVO;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.service.SpaceService;
import com.flipped.spaceservice.service.SpaceUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 空间成员服务实现（space_user 表的唯一属主）
 * <p>
 * 与单体里那份的差异只在「用户是谁」这件事上：
 * 单体可以直接读 user 表拿昵称，本服务只能经 user-service 拿（见 {@link AuthService}）。
 * 所有权限判定都只用 userId，不依赖用户资料，所以 user-service 抖动不会影响鉴权正确性。
 */
@Service
@Slf4j
public class SpaceUserServiceImpl extends ServiceImpl<SpaceUserMapper, SpaceUser> implements SpaceUserService {

    @Resource
    private SpaceService spaceService;

    @Resource
    private AuthService authService;

    @Override
    public long inviteUser(SpaceUserInviteRequest inviteRequest, Long loginUserId) {
        ThrowUtils.throwIf(inviteRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        Long spaceId = inviteRequest.getSpaceId();
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "空间 id 不能为空");
        String userAccount = inviteRequest.getUserAccount();
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount), ErrorCode.PARAMS_ERROR, "被邀请人的账号不能为空");

        // 1. 校验空间：必须是团队空间，且只有管理员可以邀请
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        ThrowUtils.throwIf(!isTeamSpace(space), ErrorCode.PARAMS_ERROR, "只有团队空间才能邀请成员");
        this.checkSpaceUserAuth(space, loginUserId, SpaceUserRoleEnum.MANAGER);

        // 2. 校验角色：只能分配 只读 / 可上传 / 可编辑
        SpaceUserRoleEnum roleEnum = SpaceUserRoleEnum.getEnumByValue(inviteRequest.getSpaceRole());
        ThrowUtils.throwIf(roleEnum == null, ErrorCode.PARAMS_ERROR, "空间角色不合法");
        ThrowUtils.throwIf(SpaceUserRoleEnum.MANAGER.equals(roleEnum), ErrorCode.PARAMS_ERROR, "不能直接邀请管理员");

        // 3. 根据账号查找被邀请人（user 表归 user-service，走内部接口）
        UserClient.InternalUser invitee = authService.getByAccount(userAccount.trim());
        ThrowUtils.throwIf(invitee == null, ErrorCode.NOT_FOUND_ERROR, "该用户不存在： " + userAccount.trim());
        ThrowUtils.throwIf(invitee.getId() == null, ErrorCode.NOT_FOUND_ERROR, "该用户不存在： " + userAccount.trim());
        ThrowUtils.throwIf(invitee.getId().equals(loginUserId), ErrorCode.PARAMS_ERROR, "不能邀请自己");

        // 4. 同一用户在同一空间只允许存在一条成员记录
        SpaceUser existed = findMember(spaceId, invitee.getId());
        Date now = new Date();
        if (existed != null) {
            Integer status = existed.getStatus();
            ThrowUtils.throwIf(SpaceUserStatusEnum.JOINED.getValue() == status,
                    ErrorCode.OPERATION_ERROR, "该用户已经是空间成员");
            ThrowUtils.throwIf(SpaceUserStatusEnum.PENDING.getValue() == status,
                    ErrorCode.OPERATION_ERROR, "已向该用户发送过邀请，请等待对方处理");
            // 之前拒绝过，重新发起邀请
            existed.setSpaceRole(roleEnum.getValue());
            existed.setStatus(SpaceUserStatusEnum.PENDING.getValue());
            existed.setInviterId(loginUserId);
            existed.setUpdateTime(now);
            boolean updated = this.updateById(existed);
            ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "邀请失败");
            return existed.getId();
        }

        SpaceUser spaceUser = new SpaceUser();
        spaceUser.setSpaceId(spaceId);
        spaceUser.setUserId(invitee.getId());
        spaceUser.setSpaceRole(roleEnum.getValue());
        spaceUser.setStatus(SpaceUserStatusEnum.PENDING.getValue());
        spaceUser.setInviterId(loginUserId);
        spaceUser.setCreateTime(now);
        spaceUser.setUpdateTime(now);
        boolean saved = this.save(spaceUser);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "邀请失败");
        log.info("邀请用户加入团队空间，空间 id = {}，被邀请人 id = {}，角色 = {}",
                spaceId, invitee.getId(), roleEnum.getText());
        return spaceUser.getId();
    }

    @Override
    public void handleInvitation(long id, boolean agree, Long loginUserId) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        SpaceUser spaceUser = this.getById(id);
        ThrowUtils.throwIf(spaceUser == null, ErrorCode.NOT_FOUND_ERROR, "邀请不存在");
        ThrowUtils.throwIf(loginUserId == null || !spaceUser.getUserId().equals(loginUserId),
                ErrorCode.NO_AUTH_ERROR, "无权处理该邀请");
        ThrowUtils.throwIf(SpaceUserStatusEnum.PENDING.getValue() != spaceUser.getStatus(),
                ErrorCode.OPERATION_ERROR, "该邀请已被处理");

        spaceUser.setStatus(agree
                ? SpaceUserStatusEnum.JOINED.getValue()
                : SpaceUserStatusEnum.REJECTED.getValue());
        spaceUser.setUpdateTime(new Date());
        boolean updated = this.updateById(spaceUser);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "处理邀请失败");
        log.info("用户 {} 处理邀请 id = {}，结果 = {}", loginUserId, id, agree ? "接受" : "拒绝");
    }

    @Override
    public void updateMemberRole(SpaceUserRoleUpdateRequest updateRequest, Long loginUserId) {
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        Long id = updateRequest.getId();
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        SpaceUser spaceUser = this.getById(id);
        ThrowUtils.throwIf(spaceUser == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        Space space = spaceService.getById(spaceUser.getSpaceId());
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        this.checkSpaceUserAuth(space, loginUserId, SpaceUserRoleEnum.MANAGER);

        ThrowUtils.throwIf(Objects.equals(SpaceUserRoleEnum.MANAGER.getValue(), spaceUser.getSpaceRole()),
                ErrorCode.PARAMS_ERROR, "不能修改空间管理员的角色");
        SpaceUserRoleEnum roleEnum = SpaceUserRoleEnum.getEnumByValue(updateRequest.getSpaceRole());
        ThrowUtils.throwIf(roleEnum == null, ErrorCode.PARAMS_ERROR, "空间角色不合法");
        ThrowUtils.throwIf(SpaceUserRoleEnum.MANAGER.equals(roleEnum), ErrorCode.PARAMS_ERROR, "不能把成员提升为管理员");

        spaceUser.setSpaceRole(roleEnum.getValue());
        spaceUser.setUpdateTime(new Date());
        boolean updated = this.updateById(spaceUser);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "修改成员权限失败");
    }

    @Override
    public void removeMember(long id, Long loginUserId) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        SpaceUser spaceUser = this.getById(id);
        ThrowUtils.throwIf(spaceUser == null, ErrorCode.NOT_FOUND_ERROR, "成员不存在");

        Space space = spaceService.getById(spaceUser.getSpaceId());
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

        boolean removeSelf = spaceUser.getUserId().equals(loginUserId);
        boolean isOwner = space.getUserId().equals(loginUserId);
        // 管理员可以移除任意普通成员，成员也可以主动退出空间
        ThrowUtils.throwIf(!isOwner && !removeSelf, ErrorCode.NO_AUTH_ERROR, "没有权限移除该成员");
        ThrowUtils.throwIf(Objects.equals(SpaceUserRoleEnum.MANAGER.getValue(), spaceUser.getSpaceRole()),
                ErrorCode.OPERATION_ERROR, "空间管理员不能被移除，如需解散请删除空间");
        // 关联表为物理删除，移除后可以重新邀请
        boolean removed = this.removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "移除成员失败");
    }

    @Override
    public Page<SpaceUserVO> listSpaceUserByPage(SpaceUserQueryRequest queryRequest, Long loginUserId) {
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR);
        Long spaceId = queryRequest.getSpaceId();
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "空间 id 不能为空");
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 只要是空间成员（含只读）就可以查看成员列表
        this.checkSpaceUserAuth(space, loginUserId, SpaceUserRoleEnum.VIEWER);

        long current = queryRequest.getCurrent();
        long size = queryRequest.getPageSize();
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 条");
        // 成员列表只展示已加入的成员
        queryRequest.setStatus(SpaceUserStatusEnum.JOINED.getValue());
        Page<SpaceUser> spaceUserPage = this.page(new Page<>(current, size), this.getQueryWrapper(queryRequest));
        return buildSpaceUserVOPage(spaceUserPage);
    }

    @Override
    public Page<SpaceUserVO> listMyInvitationByPage(SpaceUserQueryRequest queryRequest, Long loginUserId) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        SpaceUserQueryRequest request = queryRequest == null ? new SpaceUserQueryRequest() : queryRequest;
        long current = request.getCurrent();
        long size = request.getPageSize();
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 条");
        // 只查询自己的、状态为「邀请中」的记录
        request.setUserId(loginUserId);
        request.setStatus(SpaceUserStatusEnum.PENDING.getValue());
        Page<SpaceUser> spaceUserPage = this.page(new Page<>(current, size), this.getQueryWrapper(request));
        return buildSpaceUserVOPage(spaceUserPage);
    }

    @Override
    public SpaceUserRoleEnum getRoleInSpace(Space space, Long userId) {
        if (space == null || userId == null || space.getId() == null) {
            return null;
        }
        // 空间创建者天然是管理员
        if (space.getUserId() != null && space.getUserId().equals(userId)) {
            return SpaceUserRoleEnum.MANAGER;
        }
        SpaceUser spaceUser = this.lambdaQuery()
                .eq(SpaceUser::getSpaceId, space.getId())
                .eq(SpaceUser::getUserId, userId)
                .eq(SpaceUser::getStatus, SpaceUserStatusEnum.JOINED.getValue())
                .last("LIMIT 1")
                .one();
        if (spaceUser == null) {
            return null;
        }
        return SpaceUserRoleEnum.getEnumByValue(spaceUser.getSpaceRole());
    }

    @Override
    public Map<Long, SpaceUserRoleEnum> getRoleMap(List<Long> spaceIds, Long userId) {
        Map<Long, SpaceUserRoleEnum> roleMap = new HashMap<>();
        if (CollUtil.isEmpty(spaceIds) || userId == null) {
            return roleMap;
        }
        // 1. 我创建的空间天然是管理员
        spaceService.listByIds(spaceIds).forEach(space -> {
            if (space.getUserId() != null && space.getUserId().equals(userId)) {
                roleMap.put(space.getId(), SpaceUserRoleEnum.MANAGER);
            }
        });
        // 2. 其余空间一次性查出成员记录
        List<SpaceUser> memberList = this.lambdaQuery()
                .eq(SpaceUser::getUserId, userId)
                .eq(SpaceUser::getStatus, SpaceUserStatusEnum.JOINED.getValue())
                .in(SpaceUser::getSpaceId, spaceIds)
                .list();
        for (SpaceUser spaceUser : memberList) {
            SpaceUserRoleEnum roleEnum = SpaceUserRoleEnum.getEnumByValue(spaceUser.getSpaceRole());
            if (roleEnum != null) {
                roleMap.put(spaceUser.getSpaceId(), roleEnum);
            }
        }
        return roleMap;
    }

    @Override
    public void checkSpaceUserAuth(Space space, Long loginUserId, SpaceUserRoleEnum requireRole) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        SpaceUserRoleEnum role = this.getRoleInSpace(space, loginUserId);
        // requireRole 为 null 时按最低的 VIEWER 处理：调用方漏传参数不该变成「不用校验」
        SpaceUserRoleEnum required = requireRole == null ? SpaceUserRoleEnum.VIEWER : requireRole;
        if (!SpaceUserRoleEnum.hasPermission(role, required)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "没有空间权限，需要「" + required.getText() + "」或更高权限");
        }
    }

    /**
     * 查询某人在某空间的成员记录（不做状态过滤，邀请流程要用它区分「已加入 / 邀请中 / 已拒绝」）
     */
    private SpaceUser findMember(Long spaceId, Long userId) {
        return this.lambdaQuery()
                .eq(SpaceUser::getSpaceId, spaceId)
                .eq(SpaceUser::getUserId, userId)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 判断是否为团队空间（历史数据里 spaceType 可能为空，按私有空间处理）
     */
    private boolean isTeamSpace(Space space) {
        if (space == null || space.getSpaceType() == null) {
            return false;
        }
        return SpaceTypeEnum.TEAM.getValue() == space.getSpaceType();
    }

    /**
     * 批量构建封装类：填充成员信息、邀请人信息与空间信息
     * <p>
     * 用户信息来自 user-service；它不可用时这些字段为 null，**其余字段照常返回**
     * （渲染路径降级，不让依赖故障把成员列表整体打挂）。
     */
    private Page<SpaceUserVO> buildSpaceUserVOPage(Page<SpaceUser> spaceUserPage) {
        Page<SpaceUserVO> voPage = new Page<>(spaceUserPage.getCurrent(), spaceUserPage.getSize(),
                spaceUserPage.getTotal());
        List<SpaceUser> spaceUserList = spaceUserPage.getRecords();
        if (CollUtil.isEmpty(spaceUserList)) {
            return voPage;
        }
        List<SpaceUserVO> voList = spaceUserList.stream()
                .map(SpaceUserVO::objToVo)
                .collect(Collectors.toList());

        // 1. 批量查询涉及到的用户（成员 + 邀请人）
        Set<Long> userIdSet = new HashSet<>();
        spaceUserList.forEach(spaceUser -> {
            if (spaceUser.getUserId() != null) {
                userIdSet.add(spaceUser.getUserId());
            }
            if (spaceUser.getInviterId() != null) {
                userIdSet.add(spaceUser.getInviterId());
            }
        });
        Map<Long, UserVO> userMap = authService.toUserVOMap(userIdSet);
        // 2. 批量查询空间信息
        Set<Long> spaceIdSet = spaceUserList.stream()
                .map(SpaceUser::getSpaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Space> spaceMap = new HashMap<>();
        if (CollUtil.isNotEmpty(spaceIdSet)) {
            spaceMap = spaceService.listByIds(spaceIdSet).stream()
                    .collect(Collectors.toMap(Space::getId, space -> space, (a, b) -> a));
        }
        // 3. 填充
        for (SpaceUserVO spaceUserVO : voList) {
            spaceUserVO.setUser(userMap.get(spaceUserVO.getUserId()));
            spaceUserVO.setInviter(userMap.get(spaceUserVO.getInviterId()));
            Space space = spaceMap.get(spaceUserVO.getSpaceId());
            if (space != null) {
                spaceUserVO.setSpace(SpaceVO.objToVo(space));
            }
        }
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 构造空间成员查询条件
     * <p>
     * 保留在单体接口里的原语义：**始终按 createTime 倒序**，
     * 因此 SpaceUserQueryRequest 上的 sortField/sortOrder 不参与（与拆分前一致）。
     */
    private QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest queryRequest) {
        QueryWrapper<SpaceUser> queryWrapper = new QueryWrapper<>();
        if (queryRequest == null) {
            return queryWrapper;
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(queryRequest.getId()), "id", queryRequest.getId());
        queryWrapper.eq(ObjUtil.isNotEmpty(queryRequest.getSpaceId()), "spaceId", queryRequest.getSpaceId());
        queryWrapper.eq(ObjUtil.isNotEmpty(queryRequest.getUserId()), "userId", queryRequest.getUserId());
        queryWrapper.eq(ObjUtil.isNotEmpty(queryRequest.getSpaceRole()), "spaceRole", queryRequest.getSpaceRole());
        queryWrapper.eq(ObjUtil.isNotEmpty(queryRequest.getStatus()), "status", queryRequest.getStatus());
        queryWrapper.orderByDesc("createTime");
        return queryWrapper;
    }
}

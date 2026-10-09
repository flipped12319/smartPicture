package com.flipped.picturebackend.service.impl;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.feign.SpaceClient;
import com.flipped.picturebackend.manager.SpaceCacheManager;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;
import com.flipped.picturebackend.service.SpaceAuthService;
import com.flipped.picturebackend.service.SpaceUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 空间成员服务实现（单体侧）
 * <p>
 * 阶段 4b：**不再持有 space_user 表**。角色查询走
 * {@link SpaceAuthService}（本地缓存 + Feign），批量角色走
 * {@link SpaceClient#getRoleMap}。
 */
@Service
@Slf4j
public class SpaceUserServiceImpl implements SpaceUserService {

    @Resource
    private SpaceClient spaceClient;

    @Resource
    private SpaceAuthService spaceAuthService;

    @Resource
    private SpaceCacheManager spaceCacheManager;

    @Override
    public Map<Long, SpaceUserRoleEnum> getSpaceRoleMap(List<Long> spaceIds, Long userId) {
        Map<Long, SpaceUserRoleEnum> roleMap = new HashMap<>();
        if (spaceIds == null || spaceIds.isEmpty() || userId == null) {
            return roleMap;
        }
        SpaceClient.RoleMapPayload payload = new SpaceClient.RoleMapPayload();
        payload.setUserId(userId);
        payload.setSpaceIds(spaceIds);
        try {
            BaseResponse<Map<Long, Integer>> response = spaceClient.getRoleMap(payload);
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                log.warn("批量查询空间角色未成功，spaceIds = {}，userId = {}，response = {}",
                        spaceIds, userId, response);
                return roleMap;
            }
            response.getData().forEach((spaceId, role) -> {
                if (spaceId == null || role == null) {
                    return;
                }
                SpaceUserRoleEnum roleEnum = SpaceUserRoleEnum.getEnumByValue(role);
                if (roleEnum != null) {
                    roleMap.put(spaceId, roleEnum);
                    // 顺手填充角色缓存，后续单条鉴权就不必再跨服务了
                    spaceCacheManager.putRole(spaceId, userId, role);
                }
            });
        } catch (Exception e) {
            // 渲染路径：角色缺失只是少一个「可上传」标记，不该让列表打不开
            log.error("批量查询空间角色失败，spaceIds = {}，userId = {}", spaceIds, userId, e);
        }
        return roleMap;
    }

    @Override
    public void checkSpaceUserAuth(Space space, User loginUser, SpaceUserRoleEnum requireRole) {
        // 未登录必须报 40100：调用点传进来的可能是 null，**先判空再取 id**。
        // 这个顺序很关键 —— 反过来写会先 NPE，最终被兜底成 50000「系统错误」，
        // 把「你没登录」这个明确结论变成查不出来的故障（本阶段实测踩到过）。
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 整段委托给 SpaceAuthService：本地缓存 + 鉴权路径的「依赖不可用必须报错」策略都在那里。
        // 刻意不在这里再包一层 try/catch —— 吞掉异常就等于把「权限拒绝」变成「静默放行」。
        spaceAuthService.checkSpaceUserAuth(space, loginUser.getId(),
                requireRole == null ? null : requireRole.getValue());
    }
}

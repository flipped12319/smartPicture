package com.flipped.spaceservice.controller;

import com.flipped.spaceservice.common.BaseResponse;
import com.flipped.spaceservice.common.ResultUtils;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.model.dto.space.SpaceRoleQueryRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.service.SpaceService;
import com.flipped.spaceservice.service.SpaceUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 内部接口：空间成员角色查询（**只给单体的 OpenFeign 客户端用**）。
 * <p>
 * 单独拆一个 Controller 是因为 {@code /internal/**} 的路径前缀与公开接口不同，
 * 放在一起会让鉴权方式（X-Internal-Token vs JWT）在同一份代码里交织。
 * <p>
 * 这里的每个方法的字段都按「可缺省」设计：调用方漏传时返回空结果或 null，
 * 而不是抛参数异常 —— 阶段 3 的教训是把契约写成必填后，一个 null 就能引出一串
 * 完全看不出原因的报错。
 */
@RestController
@RequestMapping("/internal/space-user")
@Slf4j
public class InternalSpaceUserController {

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @Value("${internal.api.token:}")
    private String internalApiToken;

    private void checkInternalToken(String token) {
        if (internalApiToken != null && !internalApiToken.isEmpty() && !internalApiToken.equals(token)) {
            log.warn("内部接口调用被拒绝：X-Internal-Token 不匹配");
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "内部接口调用未授权");
        }
    }

    /**
     * 批量解析用户在多个空间中的角色。
     * <p>
     * 空间列表渲染时用它一次拿一整页的角色，避免逐个空间跨服务调用。
     *
     * @return spaceId -> 角色值（0-3）；不是成员的空间不会出现在返回值里
     */
    @PostMapping("/roleMap")
    public BaseResponse<Map<Long, Integer>> roleMap(@RequestBody(required = false) SpaceRoleQueryRequest request,
                                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        Map<Long, Integer> result = new HashMap<>();
        if (request == null || request.getUserId() == null || request.getSpaceIds() == null
                || request.getSpaceIds().isEmpty()) {
            return ResultUtils.success(result);
        }
        Map<Long, SpaceUserRoleEnum> roleMap =
                spaceUserService.getRoleMap(request.getSpaceIds(), request.getUserId());
        roleMap.forEach((spaceId, role) -> result.put(spaceId, role.getValue()));
        return ResultUtils.success(result);
    }

    /**
     * 解析用户在某空间中的角色；不是成员返回 null（而不是抛权限异常）
     */
    @GetMapping("/role")
    public BaseResponse<Integer> role(@RequestParam("spaceId") Long spaceId,
                                      @RequestParam("userId") Long userId,
                                      @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        if (spaceId == null || spaceId <= 0 || userId == null || userId <= 0) {
            return ResultUtils.success(null);
        }
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            return ResultUtils.success(null);
        }
        SpaceUserRoleEnum role = spaceUserService.getRoleInSpace(space, userId);
        return ResultUtils.success(role == null ? null : role.getValue());
    }
}

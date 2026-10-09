package com.flipped.spaceservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.flipped.spaceservice.common.BaseResponse;
import com.flipped.spaceservice.common.DeleteRequest;
import com.flipped.spaceservice.common.ResultUtils;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.exception.ThrowUtils;
import com.flipped.spaceservice.model.dto.space.SpaceAddRequest;
import com.flipped.spaceservice.model.dto.space.SpaceQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.entity.SpaceLevel;
import com.flipped.spaceservice.model.enums.SpaceLevelEnum;
import com.flipped.spaceservice.model.vo.SpaceVO;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.service.SpaceService;
import com.flipped.spaceservice.service.SpaceViewAssembler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 空间接口（space 表的唯一属主）
 * <p>
 * 路径与单体原来的完全一致（{@code /space/...}），所以前端与网关契约不用改 ——
 * 网关只要把 {@code /api/space/**} 路由到本服务，调用方无感知。
 * <p>
 * 与单体的差异：单体靠 {@code @AuthCheck} 注解 + AOP 做管理员校验，
 * 这里直接在方法里判断（本服务的权限点只有「管理员」一种，不值得引一套 AOP）。
 * <p>
 * 登录态：单体原来靠 Session/JWT 两套，本服务**只认 JWT**（Authorization: Bearer），
 * 因为 JWT 是无状态的，多实例下没有 Session 粘滞问题。
 */
@RestController
@RequestMapping("/space")
@Slf4j
public class SpaceController {

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceViewAssembler spaceViewAssembler;

    @Resource
    private AuthService authService;

    /**
     * 解析当前登录用户 id
     */
    private Long requireLoginUserId(String authorization) {
        return authService.resolveLoginUserId(authorization);
    }

    private void requireAdmin(Long loginUserId) {
        ThrowUtils.throwIf(!authService.isAdmin(loginUserId), ErrorCode.NO_AUTH_ERROR, "无权限");
    }

    @PostMapping("/add")
    public BaseResponse<Long> addSpace(@RequestBody SpaceAddRequest spaceAddRequest,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(spaceAddRequest == null, ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        return ResultUtils.success(spaceService.addSpace(spaceAddRequest, loginUserId));
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteSpace(@RequestBody DeleteRequest deleteRequest,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long loginUserId = requireLoginUserId(authorization);
        return ResultUtils.success(spaceService.deleteSpace(deleteRequest.getId(), loginUserId));
    }

    @PostMapping("/update")
    public BaseResponse<Boolean> updateSpace(@RequestBody SpaceUpdateRequest spaceUpdateRequest,
                                             @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (spaceUpdateRequest == null || spaceUpdateRequest.getId() == null || spaceUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        requireAdmin(requireLoginUserId(authorization));
        return ResultUtils.success(spaceService.updateSpace(spaceUpdateRequest));
    }

    /**
     * 根据用户 id 获取其私人空间 id
     * <p>
     * 只允许查询自己的空间（管理员除外）。该接口返回的是用户的私人空间 id，
     * 如果不做归属校验，任何人遍历 userId 就能拿到别人私人空间的 id，
     * 进而配合图片列表接口去读取他人私有空间的数据。
     * <p>
     * 这条链路还直接关系到智能助手：前端拿它得到 spaceId 再传给助手，
     * 一旦断了助手会**静默退化成「只搜公共图库」**。
     */
    @GetMapping("/getIdByUserId")
    public BaseResponse<Long> getSpaceIdByUserId(long userId,
                                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(userId <= 0, ErrorCode.PARAMS_ERROR);
        Long loginUserId = requireLoginUserId(authorization);
        ThrowUtils.throwIf(!loginUserId.equals(userId) && !authService.isAdmin(loginUserId),
                ErrorCode.NO_AUTH_ERROR, "只能查询自己的空间");
        Long spaceId = spaceService.getSpaceIdByUserId(userId);
        ThrowUtils.throwIf(spaceId == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(spaceId);
    }

    /**
     * 根据 id 获取空间（仅管理员可用）
     */
    @GetMapping("/get")
    public BaseResponse<Space> getSpaceById(long id,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        requireAdmin(requireLoginUserId(authorization));
        Space space = spaceService.getById(id);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(space);
    }

    /**
     * 根据 id 获取空间（封装类）
     * <p>
     * 会填充创建者信息与「当前登录用户在该空间中的角色」——
     * 前端据 currentUserRole 控制编辑/上传入口的显隐。
     */
    @GetMapping("/get/vo")
    public BaseResponse<SpaceVO> getSpaceVOById(long id,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        Space space = spaceService.getById(id);
        if (space == null) {
            // 这条日志刻意写全「是哪个 id 不存在」：40400 的默认文案是「请求数据不存在」，
            // 光看返回体分不清是 id 传错了、还是空间被删了。排查「点进去看空间报错」
            // 这类问题时，服务端这一行日志往往是最直接的证据。
            log.warn("查询空间详情失败：空间不存在，id = {}，凭据 = {}",
                    id, StrUtil.isBlank(authorization) ? "无" : "有");
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 未登录/凭据无效时不填充角色，不影响空间基本信息的查询（与单体行为一致）
        Long loginUserId = tryResolveLoginUserId(authorization);
        return ResultUtils.success(spaceViewAssembler.toVO(space, loginUserId));
    }

    /**
     * 分页获取空间列表（仅管理员可用）
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Space>> listSpaceByPage(@RequestBody SpaceQueryRequest spaceQueryRequest,
                                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(spaceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        requireAdmin(requireLoginUserId(authorization));
        return ResultUtils.success(spaceService.listSpaceByPage(spaceQueryRequest));
    }

    /**
     * 分页获取空间列表（封装类）
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<SpaceVO>> listSpaceVOByPage(@RequestBody SpaceQueryRequest spaceQueryRequest,
                                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        ThrowUtils.throwIf(spaceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long size = spaceQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Space> spacePage = spaceService.listSpaceByPage(spaceQueryRequest);
        return ResultUtils.success(spaceViewAssembler.toVOPage(spacePage, tryResolveLoginUserId(authorization)));
    }

    @GetMapping("/list/level")
    public BaseResponse<List<SpaceLevel>> listSpaceLevel() {
        List<SpaceLevel> spaceLevelList = Arrays.stream(SpaceLevelEnum.values())
                .map(spaceLevelEnum -> new SpaceLevel(
                        spaceLevelEnum.getValue(),
                        spaceLevelEnum.getText(),
                        spaceLevelEnum.getMaxCount(),
                        spaceLevelEnum.getMaxSize()))
                .collect(Collectors.toList());
        return ResultUtils.success(spaceLevelList);
    }

    /**
     * 「尽力而为」地解析登录用户：用于渲染类接口（未登录也要能看）
     */
    private Long tryResolveLoginUserId(String authorization) {
        try {
            return authService.resolveLoginUserId(authorization);
        } catch (Exception e) {
            return null;
        }
    }
}

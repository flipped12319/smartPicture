package com.flipped.spaceservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.common.BaseResponse;
import com.flipped.spaceservice.common.DeleteRequest;
import com.flipped.spaceservice.common.ResultUtils;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.exception.ThrowUtils;
import com.flipped.spaceservice.model.dto.space.SpaceAddRequest;
import com.flipped.spaceservice.model.dto.space.SpaceQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceQuotaChangeRequest;
import com.flipped.spaceservice.model.dto.space.QuotaReserveResult;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.service.SpaceService;
import com.flipped.spaceservice.service.SpaceUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 内部接口：**只给其它服务调用**（单体的 OpenFeign 客户端），不对外暴露。
 * <p>
 * 两个注意点（与 user-service 的内部接口保持一致）：
 * <ol>
 *     <li>路径前缀是 {@code /internal/**}，网关只把 {@code /api/space/**} 路由到本服务，
 *         {@code /api/internal/**} 会落到单体并返回 404 —— 所以从网关走不进来；</li>
 *     <li>绕过网关直连本服务端口时，靠 {@code X-Internal-Token} 请求头做一道最小校验。
 *         这是**开发期的轻量措施**，生产应当换成内网隔离 + mTLS 或统一的服务间鉴权。</li>
 * </ol>
 * <p>
 * 关于「登录用户是谁」：对外接口由本服务自己解析 JWT；内部接口由**调用方（单体）**
 * 解析完 JWT 后把 userId 作为参数传进来 —— 单体本来就要认登录态，没必要让它
 * 把原始 token 再转发一次、让本服务重复解析。
 */
@RestController
@RequestMapping("/internal/space")
@Slf4j
public class InternalSpaceController {

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @Value("${internal.api.token:}")
    private String internalApiToken;

    private void checkInternalToken(String token) {
        // 配置了 token 才校验，方便本地调试时留空
        if (internalApiToken != null && !internalApiToken.isEmpty() && !internalApiToken.equals(token)) {
            log.warn("内部接口调用被拒绝：X-Internal-Token 不匹配");
            // 必须抛 BusinessException：抛普通异常会被 GlobalExceptionHandler 兜底成
            // 50000「系统错误」，让调用方以为是服务故障，而不是「你没权限」
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "内部接口调用未授权");
        }
    }

    /**
     * 按 id 查空间（单体用它做鉴权与配额校验）
     */
    @GetMapping("/{id}")
    public BaseResponse<Space> getById(@PathVariable("id") Long id,
                                       @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(spaceService.getById(id));
    }

    /**
     * 批量查空间（单体渲染空间列表时一次拿一批）
     */
    @PostMapping("/listByIds")
    public BaseResponse<List<Space>> listByIds(@RequestBody List<Long> ids,
                                               @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        if (ids == null || ids.isEmpty()) {
            return ResultUtils.success(Collections.emptyList());
        }
        return ResultUtils.success(spaceService.listByIds(ids));
    }

    /**
     * 分页查询空间（管理端）
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Space>> listSpaceByPage(@RequestBody(required = false) SpaceQueryRequest queryRequest,
                                                     @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(spaceService.listSpaceByPage(queryRequest));
    }

    /**
     * 查询用户的私人空间 id（相册模块与智能助手都依赖它）
     */
    @GetMapping("/getSpaceIdByUserId")
    public BaseResponse<Long> getSpaceIdByUserId(@RequestParam("userId") Long userId,
                                                 @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(spaceService.getSpaceIdByUserId(userId));
    }

    /**
     * 「与我有关」的空间分页（我创建的 + 我加入的）
     */
    @GetMapping("/listMySpaceByPage")
    public BaseResponse<Page<Space>> listMySpaceByPage(@RequestParam("userId") Long userId,
                                                       @RequestParam(value = "current", defaultValue = "1") long current,
                                                       @RequestParam(value = "size", defaultValue = "10") long size,
                                                       @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(spaceService.listMySpaceByPage(current, size, userId));
    }

    /**
     * 创建空间（单体在**自己的本地事务里**调它，见单体的 SpaceServiceImpl#addSpace）
     */
    @PostMapping("/add")
    public BaseResponse<Long> addSpace(@RequestBody(required = false) SpaceAddRequest request,
                                       @RequestParam("userId") Long userId,
                                       @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(spaceService.addSpace(request, userId));
    }

    /**
     * 删除空间；同时清理成员记录
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteSpace(@RequestBody(required = false) DeleteRequest request,
                                             @RequestParam("userId") Long userId,
                                             @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(spaceService.deleteSpace(request.getId(), userId));
    }

    /**
     * 读取空间配额快照（单体用它做上传前的额度校验）
     */
    @GetMapping("/quota/{id}")
    public BaseResponse<Space> getQuota(@PathVariable("id") Long id,
                                        @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(spaceService.getQuota(id));
    }

    /**
     * 按增量调整空间配额，返回调整后的空间。
     * <p>
     * 这是阶段 4 **唯一的跨服务写**：单体上传/删除图片时不再自己 UPDATE space 表，
     * 改为调本接口。下一步（阶段 4c / 阶段 6）会把它换成「本地消息表 + MQ 最终一致」，
     * 所以特意做成「一次调用完成判断与写入」的形态，将来替换时调用方语义不变。
     */
    @PostMapping("/quota/change")
    public BaseResponse<Space> changeQuota(@RequestBody(required = false) SpaceQuotaChangeRequest request,
                                           @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        ThrowUtils.throwIf(request == null || request.getSpaceId() == null || request.getSpaceId() <= 0,
                ErrorCode.PARAMS_ERROR, "空间 id 不能为空");
        // 字段可缺省：不传按 0 处理（阶段 3 的教训：别用必填把调用方卡死）
        long sizeDelta = request.getSizeDelta() == null ? 0L : request.getSizeDelta();
        long countDelta = request.getCountDelta() == null ? 0L : request.getCountDelta();
        Space space = spaceService.applyQuotaDelta(request.getSpaceId(), sizeDelta, countDelta,
                request.getReason() == null ? "unspecified" : request.getReason());
        return ResultUtils.success(space);
    }

    /**
     * 原子「校验 + 预占」额度（阶段 5d-b）。
     * <p>
     * 与 {@code /quota/change} 的分工：
     * <ul>
     *     <li>本接口用于**上传占额度** —— 会因额度不足返回业务判定（{@code limited} 非空），
     *         且校验与扣减在同一条带 WHERE 的 UPDATE 里完成，并发不会超卖；</li>
     *     <li>{@code /quota/change} 用于**还额度 / 补偿** —— 无条件累加，永不因超限失败
     *         （删除必须永远能执行，否则限额配错时用户被永久卡死）。</li>
     * </ul>
     */
    @PostMapping("/quota/reserve")
    public BaseResponse<QuotaReserveResult> reserveQuota(@RequestBody(required = false) SpaceQuotaChangeRequest request,
                                                         @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        ThrowUtils.throwIf(request == null || request.getSpaceId() == null || request.getSpaceId() <= 0,
                ErrorCode.PARAMS_ERROR, "空间 id 不能为空");
        long sizeDelta = request.getSizeDelta() == null ? 0L : request.getSizeDelta();
        long countDelta = request.getCountDelta() == null ? 0L : request.getCountDelta();
        QuotaReserveResult result = spaceService.reserveQuota(request.getSpaceId(), sizeDelta, countDelta,
                request.getReason() == null ? "unspecified" : request.getReason());
        return ResultUtils.success(result);
    }

    /**
     * 校验用户在空间中的角色是否达标（单体在图片热路径上大量使用）。
     * <p>
     * 单体侧其实会先拿到 Space 再调它，这里刻意也提供「只传 spaceId」的重载，
     * 让单体在本地缓存命中时不必先跨服务取 Space。
     */
    @GetMapping("/role/check")
    public BaseResponse<Boolean> checkRole(@RequestParam("spaceId") Long spaceId,
                                           @RequestParam("userId") Long userId,
                                           @RequestParam(value = "requireRole", required = false) Integer requireRole,
                                           @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            // 空间不存在与「没有权限」要分得清：前者是 40400，后者是 40101
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        }
        SpaceUserRoleEnum required = requireRole == null
                ? SpaceUserRoleEnum.VIEWER : SpaceUserRoleEnum.getEnumByValue(requireRole);
        spaceUserService.checkSpaceUserAuth(space, userId, required);
        return ResultUtils.success(true);
    }

    /**
     * 解析用户在某空间中的角色（0-3）；不是成员返回 null。
     * <p>
     * 「渲染路径」：本方法只查库、不抛权限异常，方便调用方自己决定降级行为。
     */
    @GetMapping("/role/get")
    public BaseResponse<Integer> getRole(@RequestParam("spaceId") Long spaceId,
                                         @RequestParam("userId") Long userId,
                                         @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            return ResultUtils.success(null);
        }
        SpaceUserRoleEnum role = spaceUserService.getRoleInSpace(space, userId);
        return ResultUtils.success(role == null ? null : role.getValue());
    }
}

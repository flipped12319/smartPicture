package com.flipped.userservice.controller;

import com.flipped.userservice.common.BaseResponse;
import com.flipped.userservice.common.ResultUtils;
import com.flipped.userservice.exception.BusinessException;
import com.flipped.userservice.exception.ErrorCode;
import com.flipped.userservice.model.entity.User;
import com.flipped.userservice.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 内部接口：**只给其它服务调用**（单体的 OpenFeign 客户端），不对外暴露。
 * <p>
 * 两个注意点：
 * <ol>
 *     <li>路径前缀是 {@code /internal/**}，网关只把 {@code /api/user/**} 路由到本服务，
 *         {@code /api/internal/**} 会落到单体并返回 404 —— 所以从网关走不进来；</li>
 *     <li>绕过网关直连本服务端口时，靠 {@code X-Internal-Token} 请求头做一道最小校验。
 *         这是**开发期的轻量措施**，生产应当换成内网隔离 + mTLS 或统一的服务间鉴权。</li>
 * </ol>
 */
@RestController
@RequestMapping("/internal/user")
@Slf4j
public class InternalUserController {

    @Resource
    private UserService userService;

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

    @GetMapping("/{id}")
    public BaseResponse<User> getById(@PathVariable("id") Long id,
                                      @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(userService.getById(id));
    }

    /**
     * 批量查询：单体渲染图片/空间/相册列表时要一次拿一批作者信息。
     * 用 POST + body 传 id 列表，避免 id 多时 URL 过长。
     */
    @PostMapping("/listByIds")
    public BaseResponse<List<User>> listByIds(@RequestBody List<Long> ids,
                                              @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        if (ids == null || ids.isEmpty()) {
            return ResultUtils.success(Collections.emptyList());
        }
        return ResultUtils.success(userService.listByIds(ids));
    }

    @GetMapping("/getByAccount")
    public BaseResponse<User> getByAccount(@RequestParam("userAccount") String userAccount,
                                           @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        checkInternalToken(token);
        return ResultUtils.success(userService.lambdaQuery()
                .eq(User::getUserAccount, userAccount)
                .one());
    }
}

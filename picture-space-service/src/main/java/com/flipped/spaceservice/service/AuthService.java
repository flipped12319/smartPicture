package com.flipped.spaceservice.service;

import com.flipped.spaceservice.feign.UserClient;
import com.flipped.spaceservice.model.vo.UserVO;

import java.util.Collection;
import java.util.Map;

/**
 * 用户能力（只读）：本服务不拥有 user 表，所有用户信息都来自 user-service。
 * <p>
 * 失败策略刻意分两条路（沿用阶段 3b 定下的规矩）：
 * <ul>
 *     <li><b>鉴权路径</b>（{@link #resolveLoginUserId}）：user-service 不可用时**明确报错**，
 *         绝不静默当成「未登录」或「放行」；</li>
 *     <li><b>渲染路径</b>（{@link #toUserVO} / {@link #toUserVOMap}）：**降级返回 null**，
 *         让成员列表照常展示，只是暂时没有昵称。</li>
 * </ul>
 */
public interface AuthService {

    /**
     * 从 Authorization 头解析当前登录用户的 id。
     * <p>
     * 没有任何凭据 / token 非法 / token 对应用户已不存在时，一律抛 40100「未登录」。
     */
    Long resolveLoginUserId(String authorizationHeader);

    /**
     * 当前登录用户是否平台管理员。user-service 不可用时返回 false（**失败关闭**，
     * 不能因为依赖挂了就把管理员权限发给所有人），并且这里不是登录路径，不需要抛异常。
     */
    boolean isAdmin(Long userId);

    /**
     * 按 id 取脱敏用户信息（渲染路径，失败返回 null）
     */
    UserVO toUserVO(Long userId);

    /**
     * 批量取脱敏用户信息（渲染路径，失败返回空 Map）
     *
     * @return userId -> UserVO
     */
    Map<Long, UserVO> toUserVOMap(Collection<Long> userIds);

    /**
     * 按账号取用户（邀请成员用）。账号不存在或 user-service 不可用都返回 null，
     * 由调用方给出「该用户不存在」的提示。
     */
    UserClient.InternalUser getByAccount(String userAccount);
}

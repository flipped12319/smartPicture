package com.flipped.userservice.constant;

public interface UserConstant {

    /**
     * 用户登录态键（过渡期兜底用；JWT 是主凭据）
     */
    String USER_LOGIN_STATE = "user_login";

    /**
     * 默认角色
     */
    String DEFAULT_ROLE = "user";

    /**
     * 管理员角色
     */
    String ADMIN_ROLE = "admin";
}

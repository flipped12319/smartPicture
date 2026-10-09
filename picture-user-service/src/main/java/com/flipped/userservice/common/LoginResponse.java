package com.flipped.userservice.common;

import com.flipped.userservice.model.vo.LoginUserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应：脱敏用户信息 + JWT。
 * 字段名与单体一致，前端从 res.data.data.token 取 token。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private LoginUserVO userInfo;
    private String token;
}

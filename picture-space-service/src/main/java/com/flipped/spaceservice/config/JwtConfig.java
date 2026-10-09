package com.flipped.spaceservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置
 * <p>
 * ⚠️ secret 必须与单体（picture-backend）、user-service 完全一致：
 * token 由 user-service 签发，本服务只负责校验。密钥不同会导致
 * 「登录成功，但访问空间接口全都提示未登录」。
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    private String secret;
    private Long expiration;
}

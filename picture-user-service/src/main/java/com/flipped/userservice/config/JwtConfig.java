package com.flipped.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置
 * <p>
 * ⚠️ secret 必须与单体（picture-backend）完全一致：
 * 本服务签发、单体校验，密钥不同会导致「登录成功但所有后续请求都未登录」。
 */
@Component
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    private String secret;
    private Long expiration;
}

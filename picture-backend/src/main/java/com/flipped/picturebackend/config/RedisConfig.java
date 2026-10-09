package com.flipped.picturebackend.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis 客户端配置
 * <p>
 * 缓存属于「可降级」组件：Redis 不可用时应尽快失败并回落到数据库，
 * 而不是让用户请求长时间挂起。这里把 Lettuce 的超时收紧到 1 秒。
 * <p>
 * 注意：本配置会覆盖 application.yml 中 spring.redis.timeout 的设置
 * （原值为 5000 毫秒）。如需调整，请直接修改下面的常量。
 */
@Configuration
public class RedisConfig {

    /**
     * 单条命令的超时时间
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);

    /**
     * 建立 TCP 连接的超时时间
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceTimeoutCustomizer() {
        // 当前 application.yml 中未配置 spring.redis.lettuce.* 相关选项，
        // 因此这里直接构建 ClientOptions 不会有覆盖用户配置的风险
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build())
                .build();
        return builder -> builder
                .commandTimeout(COMMAND_TIMEOUT)
                .clientOptions(clientOptions);
    }
}

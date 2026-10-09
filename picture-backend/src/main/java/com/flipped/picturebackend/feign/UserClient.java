package com.flipped.picturebackend.feign;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.model.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * user-service 的内部接口客户端（阶段 3b）
 * <p>
 * 单体不再读写 user 表，所有用户读取都走这里。
 * <p>
 * 地址策略（刻意保留静态 url 作为默认）：
 * 配置了 {@code user.api.base-url} 就直接连它 —— **不依赖 Nacos**，Nacos 挂了也能开发；
 * 把该配置置空则退化为「按服务名 + 负载均衡」走服务发现。
 * 这与项目里其它依赖的「默认不依赖外部组件」原则保持一致。
 */
@FeignClient(name = "picture-user-service",
        url = "${user.api.base-url:http://127.0.0.1:8126}",
        configuration = UserClient.InternalTokenConfig.class)
public interface UserClient {

    @GetMapping("/api/internal/user/{id}")
    BaseResponse<User> getById(@PathVariable("id") Long id);

    @PostMapping("/api/internal/user/listByIds")
    BaseResponse<List<User>> listByIds(@RequestBody List<Long> ids);

    @GetMapping("/api/internal/user/getByAccount")
    BaseResponse<User> getByAccount(@RequestParam("userAccount") String userAccount);

    /**
     * 内部调用统一带上约定的 token。
     * <p>
     * 刻意写成**不带 @Configuration 的静态内部类**：Feign 的 configuration 若被 Spring
     * 当普通配置类扫描到，会变成全局生效，污染其它 Feign 客户端。
     */
    class InternalTokenConfig {

        @Value("${internal.api.token:}")
        private String internalApiToken;

        @Bean
        public feign.RequestInterceptor internalTokenInterceptor() {
            return template -> {
                if (internalApiToken != null && !internalApiToken.isEmpty()) {
                    template.header("X-Internal-Token", internalApiToken);
                }
            };
        }
    }
}

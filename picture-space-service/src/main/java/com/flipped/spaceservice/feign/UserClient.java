package com.flipped.spaceservice.feign;

import com.flipped.spaceservice.common.BaseResponse;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * user-service 的内部接口客户端（阶段 4）
 * <p>
 * 本服务不拥有 user 表，但有两件事需要用户信息：
 * <ol>
 *     <li>校验登录态（token → userId，顺带确认用户仍然存在）；</li>
 *     <li>渲染 space_user 列表里的「成员」与「邀请人」昵称/头像。</li>
 * </ol>
 * <p>
 * 地址策略与单体调 user-service 完全一致：配置了 {@code user.api.base-url} 就直接连它，
 * **不依赖 Nacos**；置空则退化为按服务名 + 负载均衡走服务发现。
 */
@FeignClient(name = "picture-user-service",
        url = "${user.api.base-url:http://127.0.0.1:8126}",
        configuration = UserClient.InternalTokenConfig.class)
public interface UserClient {

    /**
     * 按 id 查用户；用户不存在时内部接口返回 code != 0
     */
    @GetMapping("/api/internal/user/{id}")
    BaseResponse<InternalUser> getById(@PathVariable("id") Long id);

    /**
     * 按账号查用户（邀请成员时用）
     */
    @GetMapping("/api/internal/user/getByAccount")
    BaseResponse<InternalUser> getByAccount(@RequestParam("userAccount") String userAccount);

    /**
     * 跨服务传输的用户快照。刻意**只保留展示与鉴权需要的字段**，
     * 且不包含密码 —— 内部接口也不该把密码哈希传来传去。
     */
    @Data
    class InternalUser {
        private Long id;
        private String userAccount;
        private String userName;
        private String userAvatar;
        private String userProfile;
        private String userRole;
    }

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

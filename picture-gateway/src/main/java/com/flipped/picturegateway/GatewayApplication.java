package com.flipped.picturegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一网关（阶段 1）
 * <p>
 * 目前只做三件事：
 * <ol>
 *     <li>把浏览器请求统一收敛到一个入口（默认 9000），转发给现有单体（8123）；</li>
 *     <li>在网关层统一处理 CORS，替代「每个服务各自配跨域」；</li>
 *     <li>生成并透传 traceId，为后续跨服务排查打底。</li>
 * </ol>
 * 阶段 2 会加入 Nacos 做服务发现与配置中心，届时下面这些静态路由会换成
 * {@code lb://picture-backend}。
 * <p>
 * 注意：<b>本阶段刻意不在网关做鉴权</b>。单体的鉴权是「HTTP Session 为主 +
 * 部分接口 Bearer 兜底」，在网关重复实现一套路径级的鉴权规则，既容易与单体
 * 不一致，也会挡住那些依赖 Session 的接口。真正的鉴权收敛放到阶段 3
 * （user-service 成型、JWT 成为唯一凭据）之后再做。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

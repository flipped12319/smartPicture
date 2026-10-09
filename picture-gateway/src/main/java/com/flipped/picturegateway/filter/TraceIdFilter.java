package com.flipped.picturegateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 链路标识过滤器
 * <p>
 * 微服务化之后，一次请求会跨多个服务，出问题时如果只有各服务自己的日志，
 * 根本串不起「这一次请求到底经过了哪些节点」。这里先在网关做最小可用的一步：
 * <ol>
 *     <li>请求没带 {@code X-Trace-Id} 就生成一个，并透传给下游；</li>
 *     <li>把同一个值写回响应头，前端/调用方可以据此反馈问题；</li>
 *     <li>打一条包含 method / path / 状态码 / 耗时的访问日志。</li>
 * </ol>
 * 放在 {@link Ordered#HIGHEST_PRECEDENCE}，保证它在其它过滤器之前拿到原始请求，
 * 这样即使后续某个过滤器直接短路返回，响应里也带着 traceId。
 * <p>
 * 为什么用全局过滤器而不是路由过滤器：traceId 应该对**所有**路由生效，
 * 包括以后新增的服务，写在路由上会漏。
 * <p>
 * 做成可开关（{@code gateway.trace-id.enabled=false} 可关闭）是为了排查方便：
 * 全局过滤器会作用于 WebSocket 路由，一旦怀疑它干扰握手/回包，能不改代码就关掉对比。
 */
@Component
@ConditionalOnProperty(name = "gateway.trace-id.enabled", havingValue = "true", matchIfMissing = true)
public class TraceIdFilter implements GlobalFilter, Ordered {

    /**
     * 链路标识的请求/响应头名称，遵循常见的 X-Trace-Id 约定
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incomingTraceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        boolean generated = !StringUtils.hasText(incomingTraceId);
        // 声明为 final：下面的 lambda 只会捕获 effectively final 的变量，
        // 如果先赋值再改写，编译器会直接报错
        final String traceId = generated ? newTraceId() : incomingTraceId;

        ServerWebExchange effectiveExchange = exchange;
        if (generated) {
            // 必须 mutate 出新的 exchange，否则新加的 header 不会传给下游服务
            effectiveExchange = exchange.mutate()
                    .request(builder -> builder.header(TRACE_ID_HEADER, traceId))
                    .build();
        }

        // 回写给调用方。mutate 与原始 exchange 共用同一个 response 对象，所以这里设置是生效的
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);

        final HttpMethod method = request.getMethod();
        final String path = request.getURI().getRawPath();
        final long startMillis = System.currentTimeMillis();

        // 用 doFinally 而不是 then：无论正常返回、异常还是客户端断开都会记一条日志，
        // 否则「请求卡住后断开」这类最需要排查的情况反而没有日志
        return chain.filter(effectiveExchange).doFinally(signal -> {
            HttpStatus status = exchange.getResponse().getStatusCode();
            log.info("[gw] {} {} -> {} ({} ms) traceId={}",
                    method == null ? "-" : method.name(),
                    path,
                    status == null ? "-" : status.value(),
                    System.currentTimeMillis() - startMillis,
                    traceId);
        });
    }

    /**
     * 生成链路标识
     * <p>
     * 用「时间戳(36 进制) + 随机短串」而不是纯 UUID：既保持唯一，
     * 又能让人从字符串本身看出大致发生时间，排查时更直观。
     */
    private String newTraceId() {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return Long.toString(System.currentTimeMillis(), 36) + random;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

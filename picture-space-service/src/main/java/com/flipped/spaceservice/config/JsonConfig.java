package com.flipped.spaceservice.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把所有 Long 序列化成**字符串**（与单体 picture-backend 的 JsonConfig 完全一致）。
 *
 * <p><b>这不是可选项，是正确性要求。</b>本服务的 id 是雪花 id（19 位，约 2.1e18），
 * 而 JavaScript 的 Number 只能精确表示到 2^53-1（9007199254740991，16 位）。
 * 一旦以 JSON 数字下发，前端拿到的那一刻精度就已经丢了 —— 而且**丢了之后看不出任何异常**：
 * 列表能显示、名字能显示，只有拿这个 id 去请求时才 404。
 *
 * <p>阶段 4 的真实事故：本服务漏了这份配置，`/api/spaceUser/my/space/list/page`
 * 把 id 当数字下发，前端点进空间后请求的就是被四舍五入过的 id：
 * <pre>
 *   真实 2107713929437249537  ->  前端 Number  ->  请求 2107713929437249500
 *   真实 2100208978036293634  ->  前端 Number  ->  请求 2100208978036293600
 * </pre>
 * 表现是「创建完团队空间，点进去看：获取数据失败 / 请求数据不存在」，
 * 而库里空间好好的 —— 因为查的根本不是同一个 id。同一份配置在单体里有，
 * 所以单体自己的接口没这个问题，只有拆出来的新服务会踩到。
 *
 * <p>Feign 侧不受影响：单体用 {@code BaseResponse<Space>} 接收，Jackson 能把
 * {@code "2107713929437249537"} 反序列化成 Long。
 */
@Configuration
public class JsonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modules(module);
        };
    }
}

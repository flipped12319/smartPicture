package com.flipped.userservice.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把所有 Long 序列化成**字符串**（与单体 picture-backend 的 JsonConfig 完全一致）。
 *
 * <p><b>这不是可选项，是正确性要求。</b>用户 id 是雪花 id（19 位，约 2.1e18），
 * 而 JavaScript 的 Number 只能精确表示到 2^53-1（9007199254740991，16 位）。
 * 一旦以 JSON 数字下发，前端拿到的那一刻精度就已经丢了。
 *
 * <p>这份配置是补漏：单体一直有，而本服务（阶段 3b）与 picture-space-service（阶段 4）
 * 都漏了。空间侧的漏配引发了真实事故 —— 前端拿到被四舍五入的空间 id，
 * 点进去就报「空间不存在」。用户侧同源：`/user/get/login` 返回的 id 与
 * `/user/list/page/vo` 里的 id 都会丢精度（例如前端按 id 找用户会找不到）。
 *
 * <p>Feign 侧不受影响：单体用 {@code BaseResponse<User>} 接收，Jackson 能把
 * {@code "2052376987161366530"} 反序列化成 Long。
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

package com.flipped.spaceservice.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * 统一消息信封（阶段 5d，与单体侧 {@code picture-backend} 的 MqMessage 结构一致）
 *
 * <p><b>为什么这里要再写一份，而不是抽公共模块</b>：
 * 这个项目的服务刻意保持「各自独立可构建、无共享代码模块」—— 抽一个 common 模块会引入
 * 版本耦合与发布顺序问题，对这个规模的代码不值得。代价是每个服务各留一份 ~50 行的定义，
 * 这是**知情的取舍**：结构必须与生产端逐字段一致，否则反序列化会静默丢字段。
 *
 * <p>字段一律按「可缺省」设计（阶段 3 的 422 教训）：解析不出来时记录并拒绝消息，
 * 而不是因为一个字段缺失就整批失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MqMessage<T> implements Serializable {

    /**
     * 全局唯一消息 id —— 幂等去重的键，必须由**生产端**生成
     */
    private String messageId;

    /**
     * 消息类型，取值与路由键一致（如 {@code quota.changed}）
     */
    private String messageType;

    /**
     * 产生这条消息的服务名
     */
    private String source;

    /**
     * 产生时间
     */
    private Date occurredAt;

    /**
     * 业务负载
     */
    private T payload;

    private static final long serialVersionUID = 1L;

    public static <T> MqMessage<T> of(String messageType, String source, T payload) {
        return new MqMessage<>(UUID.randomUUID().toString(), messageType, source, new Date(), payload);
    }
}

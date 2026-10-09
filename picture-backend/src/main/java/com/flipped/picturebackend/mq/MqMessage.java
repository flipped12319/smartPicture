package com.flipped.picturebackend.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * 统一消息信封（阶段 5a）
 *
 * <p><b>为什么要有信封，而不是直接发业务对象</b>：
 * <ol>
 *     <li>{@code messageId} 是**幂等去重的依据**。没有它就没法判断「这条消息我是不是见过」，
 *         而 MQ 的语义是「至少一次」，重复投递是常态而非异常；</li>
 *     <li>排查线上问题时，第一条要问的是「这条消息什么时候、由哪个服务、因为什么事件产生的」——
 *         这些元信息不该混在业务字段里；</li>
 *     <li>业务 payload 可以独立演进，信封保持不变。</li>
 * </ol>
 *
 * <p><b>字段一律按「可缺省」设计</b>（阶段 3 的 422 教训）：消费端解析不出来时应记录并
 * 进死信，而不是因为一个字段缺失整批失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MqMessage<T> implements Serializable {

    /**
     * 全局唯一消息 id —— 幂等去重的键。
     * <p>
     * 由**生产端**生成而不是 broker 生成：本地消息表要求「消息在写业务数据时就已经有 id」
     * （见 {@code local_message.messageId}），broker 生成的 id 是投递时才有的，那对不上。
     */
    private String messageId;

    /**
     * 消息类型，取值与路由键一致（如 {@code picture.index.requested}），便于人读与告警分类
     */
    private String messageType;

    /**
     * 产生这条消息的服务名，多服务排查时能立刻看出源头
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

    /**
     * 构造一个新消息（自动生成 messageId 与时间戳）
     */
    public static <T> MqMessage<T> of(String messageType, String source, T payload) {
        return new MqMessage<>(UUID.randomUUID().toString(), messageType, source, new Date(), payload);
    }

    /**
     * 用**指定的** messageId 构造：本地消息表补投时必须用它，
     * 否则每次补投都会生成新 id，消费端的幂等去重就完全失效了。
     */
    public static <T> MqMessage<T> of(String messageId, String messageType, String source, T payload, Date occurredAt) {
        return new MqMessage<>(messageId, messageType, source, occurredAt == null ? new Date() : occurredAt, payload);
    }
}

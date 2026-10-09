package com.flipped.picturebackend.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消息发送（阶段 5a）
 *
 * <p>目前只负责「把信封发到交换机」。5c 引入本地消息表后，业务侧的调用会变成
 * 「事务内写 local_message」，由定时任务通过 {@link #publish} 补投 ——
 * 所以这里刻意做成**纯粹的发送动作**，不掺业务判断，将来两处复用同一个出口。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqPublisher {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送到业务主交换机。
     *
     * <p><b>失败时抛异常，不吞成 false</b>（实测踩过）：
     * 调用方 {@code MessageRelay.publishNow} 会把失败原因写进
     * {@code local_message.lastError} —— 那是排查「为什么这条消息发不出去」时唯一的信息。
     * 如果这里把 {@code Connection refused} 吞掉只返回 false，本地消息表里就只剩一句
     * 「publish returned false」，完全看不出 MQ 是不是挂了。
     *
     * @param routingKey 与 {@code MqMessage.messageType} 保持一致，便于管理台按类型检索
     */
    public void publish(String routingKey, MqMessage<?> message) {
        log.info("准备投递消息：type = {}，messageId = {}，routingKey = {}",
                message.getMessageType(), message.getMessageId(), routingKey);
        // 不 try-catch：让连接异常/序列化异常原样抛给调用方（见方法注释）
        rabbitTemplate.convertAndSend(MqConfig.EXCHANGE, routingKey, message);
        log.info("消息已交给 broker：type = {}，messageId = {}", message.getMessageType(), message.getMessageId());
    }

    /**
     * 发送到延迟重试交换机：消息挂 TTL，过期后自动死信回业务交换机重新消费。
     * <p>
     * 用它替代「消费线程里 sleep 重试」—— 后者会占住线程，正是阶段 5 要解决的问题。
     * <p>
     * 与 {@link #publish} 一致：失败抛异常，不吞成 false。
     */
    public void publishToRetry(String retryQueue, MqMessage<?> message) {
        rabbitTemplate.convertAndSend(MqConfig.RETRY_EXCHANGE, retryQueue, message);
        log.warn("消息转入延迟重试：type = {}，messageId = {}，retryQueue = {}",
                message.getMessageType(), message.getMessageId(), retryQueue);
    }
}

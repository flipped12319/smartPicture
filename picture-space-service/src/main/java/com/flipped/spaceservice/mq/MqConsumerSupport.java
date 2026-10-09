package com.flipped.spaceservice.mq;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * MQ 消费的统一入口（阶段 5d，与单体侧 MqConsumerSupport 同一套做法）
 *
 * <p>一次正确的消费要同时做对四件事，任何一件漏了都会出问题，而且症状各不相同：
 * <ol>
 *     <li><b>幂等去重</b>：MQ 是「至少一次」，不查重就会重复执行副作用（配额被扣两次）；</li>
 *     <li><b>成功 ack</b>：手动 ack 模式下不 ack，消息会永远卡在 unacked，
 *         而 prefetch=1 会把后续消息**全堵住**（实测踩过，见单体侧的同名注释）；</li>
 *     <li><b>失败撤销去重并拒绝</b>：只 ack 不撤销，重投会被当成重复消息丢掉，消息就真丢了；
 *         只拒绝不撤销，重投会重复执行副作用。两个都必须做；</li>
 *     <li><b>拒绝时 requeue=false</b>：否则失败消息被 broker 立刻重投，瞬间打满 CPU。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqConsumerSupport {

    @Resource
    private MessageIdempotency idempotency;

    /**
     * 执行一次「幂等 + ack」的消费。
     *
     * @param consumer 消费者标识（建议传队列名）：同一条消息被不同消费者各消费一次是允许的
     * @param business 真正的业务处理，允许抛受检异常
     */
    public <T> void consume(MqMessage<T> message, Channel channel, long deliveryTag,
                            String consumer, MqBusiness<T> business) throws IOException {
        if (message == null) {
            log.warn("收到空消息，直接 ack 丢弃，consumer = {}", consumer);
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (!idempotency.claim(message.getMessageId(), consumer, message.getMessageType())) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            business.run(message.getPayload());
            channel.basicAck(deliveryTag, false);
            // messageId 必须打在**公共层**：它是排查 MQ 问题的唯一线索，
            // 靠它才能把「投递 → 消费 → 去重」串起来。指望每个业务都记得打，就一定会漏。
            log.info("消息处理成功：messageId = {}，type = {}，consumer = {}，耗时 = {}ms",
                    message.getMessageId(), message.getMessageType(), consumer,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 顺序很重要：先撤销幂等登记（让重投能真正执行），再拒绝消息
            idempotency.release(message.getMessageId(), consumer);
            log.error("消息处理失败，已拒绝（requeue=false，将进入死信或延迟重试）：" +
                            "messageId = {}，type = {}，consumer = {}，耗时 = {}ms",
                    message.getMessageId(), message.getMessageType(), consumer,
                    System.currentTimeMillis() - start, e);
            channel.basicReject(deliveryTag, false);
        }
    }

    /**
     * 业务处理动作，允许抛受检异常，由 {@link #consume} 统一决定失败怎么处理。
     */
    @FunctionalInterface
    public interface MqBusiness<T> {
        void run(T payload) throws Exception;
    }
}

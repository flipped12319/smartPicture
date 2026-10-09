package com.flipped.picturebackend.mq;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * MQ 消费的统一入口（阶段 5a）
 *
 * <p><b>为什么必须包一层</b>：一次正确的消费要同时做对四件事，任何一件漏了都会出问题，
 * 而且症状各不相同、都不容易联想到原因：
 * <ol>
 *     <li><b>幂等去重</b>：MQ 是「至少一次」，不查重就会重复执行副作用（配额被扣两次）；</li>
 *     <li><b>成功 ack</b>：手动 ack 模式下不 ack，消息会永远卡在 unacked
 *         （见 {@code MqTestListener} 的注释，这里踩过）；</li>
 *     <li><b>失败撤销去重并拒绝</b>：只 ack 不撤销，重投会被当成重复消息丢弃，**消息就真丢了**；
 *         只拒绝不撤销，重投会重复执行副作用。两个都必须做；</li>
 *     <li><b>拒绝时 requeue=false</b>：否则失败消息被 broker 立刻重投，瞬间打满 CPU。</li>
 * </ol>
 * 把它们收在一个方法里，业务消费者只需要写「拿到 payload 做什么」。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * @RabbitListener(queues = MqConfig.INDEX_QUEUE)
 * public void onIndex(MqMessage<IndexPayload> msg, Channel channel,
 *                     @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
 *     consumers.consume(msg, channel, tag, QUEUE,
 *             payload -> pictureIndexService.doIndexNow(payload.getPictureId()));
 * }
 * }</pre>
 *
 * <p><b>为什么用 Supplier 而不是 Consumer</b>：{@code Supplier} 允许业务抛出受检异常
 * （例如调用 HTTP 接口），{@code Consumer} 只能抛运行时异常，会把业务代码逼成到处 try-catch。
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
     * @param message     消息信封（不可为 null，否则直接 ack 丢弃）
     * @param channel     消费信道，用于 ack / reject
     * @param deliveryTag 投递标识
     * @param consumer    消费者标识（建议传队列名）：同一条消息被不同消费者各消费一次是允许的
     * @param business    真正的业务处理，返回值忽略
     */
    public <T> void consume(MqMessage<T> message, Channel channel, long deliveryTag,
                            String consumer, MqBusiness<T> business) throws IOException {
        if (message == null) {
            log.warn("收到空消息，直接 ack 丢弃，consumer = {}", consumer);
            channel.basicAck(deliveryTag, false);
            return;
        }
        if (!idempotency.claim(message.getMessageId(), consumer, message.getMessageType())) {
            // 重复投递：业务侧已经处理过（或正在处理），ack 让 broker 删掉这条
            channel.basicAck(deliveryTag, false);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            business.run(message.getPayload());
            channel.basicAck(deliveryTag, false);
            // 这条日志（以及下面的失败日志）**统一打在这里，而不是让每个业务自己打**：
            // messageId 是排查 MQ 问题的唯一线索 —— 靠它才能把「投递 → 消费 → 去重」串起来。
            // 如果指望业务日志顺带打 messageId，就一定会漏（实测踩过：业务日志只打了 pictureId，
            // 于是按 messageId 过滤时看起来「这条消息根本没被消费」）。
            // 横切关注点就应该在公共层解决。
            log.info("消息处理成功：messageId = {}，type = {}，consumer = {}，耗时 = {}ms",
                    message.getMessageId(), message.getMessageType(), consumer,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 顺序很重要：先撤销幂等登记（让重投能真正执行），再拒绝消息
            idempotency.release(message.getMessageId(), consumer);
            log.error("消息处理失败，已拒绝（requeue=false，将进入死信或延迟重试）：messageId = {}，type = {}，consumer = {}，耗时 = {}ms",
                    message.getMessageId(), message.getMessageType(), consumer,
                    System.currentTimeMillis() - start, e);
            channel.basicReject(deliveryTag, false);
        }
    }

    /**
     * 业务处理动作。允许抛受检异常，由 {@link #consume} 统一决定怎么处理失败。
     */
    @FunctionalInterface
    public interface MqBusiness<T> {
        void run(T payload) throws Exception;
    }
}

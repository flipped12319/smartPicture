package com.flipped.picturebackend.mq;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * 5a 自检消费者：只打日志，不做任何业务。
 *
 * <p><b>为什么先要一个「什么都不做」的消费者</b>：
 * MQ 的地基（拓扑声明、JSON 转换、幂等去重、手动 ack、死信）本身就有不少容易错的地方。
 * 先用一条不碰数据的队列把它验证通，后面让索引和配额依赖它时，
 * 出问题就能明确是「业务逻辑错了」而不是「地基没搭好」。
 *
 * <p>幂等验证方法：用同一个 {@code messageId} 连发两次，
 * 第一次会看到「收到测试消息」，第二次会看到「重复消息已被幂等去重丢弃」。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqTestListener {

    @Resource
    private MqConsumerSupport consumers;

    @RabbitListener(queues = MqConfig.TEST_QUEUE)
    public void onTestMessage(MqMessage<String> message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 日志里必须带上 messageId：**排查时是按 messageId 把「投递-消费-去重」三条串起来的**，
        // 少了它就查不出「这条消息到底处理了没有」。（这里踩过一次：只打了 payload，
        // 于是验证脚本按 messageId 过滤日志时一条都匹配不到，看起来像「根本没消费」。）
        consumers.consume(message, channel, deliveryTag, MqConfig.TEST_QUEUE,
                payload -> log.info("【5a 自检】收到测试消息并处理成功：messageId = {}，payload = {}",
                        message == null ? null : message.getMessageId(), payload));
    }
}

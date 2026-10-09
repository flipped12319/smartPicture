package com.flipped.spaceservice.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * 5d-a 自检消费者：只打日志，不做任何业务。
 *
 * <p><b>为什么先要它</b>：这是本服务的**第一个 MQ 消费者**。消费地基（拓扑声明、
 * JSON 转换、幂等去重、手动 ack、死信路由）本身有很多容易错的地方，
 * 先用一条不碰数据的队列验证通，后面让配额依赖它时，出问题就能确定是「配额逻辑错了」
 * 而不是「地基没搭好」—— 这条经验在单体侧（5a）已经证明有效。
 *
 * <p>幂等验证：用同一个 {@code messageId} 连发两次，第二次会看到「重复消息已被幂等去重丢弃」。
 * 投递方式（本服务目前只消费、不生产）：
 * <pre>
 *   POST {单体}/api/mq/debug/publish-space-test?messageId=xxx&text=hello
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class SpaceTestListener {

    @Resource
    private MqConsumerSupport consumers;

    @RabbitListener(queues = MqConfig.TEST_QUEUE)
    public void onTestMessage(MqMessage<String> message,
                              com.rabbitmq.client.Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumers.consume(message, channel, deliveryTag, MqConfig.TEST_QUEUE,
                payload -> log.info("【5d-a 自检】space-service 收到测试消息并处理成功：messageId = {}，payload = {}",
                        message == null ? null : message.getMessageId(), payload));
    }
}

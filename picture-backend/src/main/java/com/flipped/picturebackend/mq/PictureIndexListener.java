package com.flipped.picturebackend.mq;

import com.flipped.picturebackend.service.PictureIndexService;
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
 * 索引请求消费者（阶段 5b）
 *
 * <p>职责边界刻意划得很清：
 * <ul>
 *     <li><b>它负责</b>：幂等、ack、失败重试路由（交给 {@link MqConsumerSupport}）；</li>
 *     <li><b>它不负责</b>：索引本身怎么做 —— 那是 {@code PictureIndexService} 的事。
 *         这样「MQ 通道」与「业务逻辑」可以分开测试与回滚。</li>
 * </ul>
 *
 * <p><b>为什么业务异常要往外抛</b>：{@code indexPictureNow} 在模型调用失败时会抛异常，
 * 于是 {@link MqConsumerSupport} 会撤销幂等登记并 reject（进死信/延迟重试）。
 * 反过来，如果这里把异常吞掉只记日志，消息会被正常 ack ——
 * 那条失败的消息就**再也回不来了**，`indexStatus=2` 只能等人工重建，
 * 这正是阶段 5 要修掉的老毛病。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class PictureIndexListener {

    @Resource
    private MqConsumerSupport consumers;

    @Resource
    private PictureIndexService pictureIndexService;

    @RabbitListener(queues = MqConfig.INDEX_QUEUE)
    public void onIndexRequested(MqMessage<PictureIndexPayload> message,
                                 Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumers.consume(message, channel, deliveryTag, MqConfig.INDEX_QUEUE, payload -> {
            if (payload == null || payload.getPictureId() == null) {
                // 没有 pictureId 的消息没有任何补救余地，直接当成功消费掉（ack），
                // 否则它会一直重投、堆满死信队列
                log.warn("索引消息缺少 pictureId，直接忽略：messageId = {}",
                        message == null ? null : message.getMessageId());
                return;
            }
            pictureIndexService.indexPictureNow(payload.getPictureId(), payload.shouldFillBlankFields());
        });
    }
}

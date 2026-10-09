package com.flipped.spaceservice.mq;

import com.flipped.spaceservice.model.dto.space.QuotaChangedPayload;
import com.flipped.spaceservice.service.SpaceService;
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
 * 额度变更消费者（阶段 5d-b，§4.6 配额最终一致）
 *
 * <p>单体删除图片时会发 {@code quota.changed}，由本服务把额度加回去 ——
 * 因为 **space 表只有本服务有写权限**。
 *
 * <h3>为什么这里必须幂等</h3>
 * 增量语义下「重复消费 = 重复扣减」。幂等由 {@link MqConsumerSupport} 用
 * {@code consumed_message} 表保证（messageId + consumer 唯一）。
 *
 * <h3>空间已被删除时怎么办（真实会发生的边界）</h3>
 * 完全可能：用户删图片、紧接着把空间也删了，消息才被消费。
 * 此时**跳过并 ack**，而不是抛异常重试 —— 空间都没了，没有额度需要归还，
 * 重试只会让消息在死信队列里越堆越多（永远不可能成功）。
 * 这个判断必须显式写出来，否则 `applyQuotaDelta` 会抛 40400，
 * 然后那条消息会一直重投到上限、进死信、需要人工清理。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class QuotaChangedListener {

    @Resource
    private MqConsumerSupport consumers;

    @Resource
    private SpaceService spaceService;

    @RabbitListener(queues = MqConfig.QUOTA_QUEUE)
    public void onQuotaChanged(MqMessage<QuotaChangedPayload> message,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumers.consume(message, channel, deliveryTag, MqConfig.QUOTA_QUEUE, payload -> {
            String messageId = message == null ? null : message.getMessageId();
            if (payload == null || payload.getSpaceId() == null || payload.getSpaceId() <= 0) {
                // 没有 spaceId 的消息没有任何补救余地，当成功消费掉（ack），
                // 否则它会一直重投、堆满死信队列
                log.warn("额度变更消息缺少 spaceId，直接忽略：messageId = {}", messageId);
                return;
            }
            Long spaceId = payload.getSpaceId();
            if (spaceService.getQuota(spaceId) == null) {
                log.warn("额度变更跳过：空间已不存在（可能是删图片后又删了空间）：" +
                                "spaceId = {}，sizeDelta = {}，countDelta = {}，messageId = {}",
                        spaceId, payload.sizeDeltaOrZero(), payload.countDeltaOrZero(), messageId);
                return;
            }
            spaceService.applyQuotaDelta(spaceId, payload.sizeDeltaOrZero(), payload.countDeltaOrZero(),
                    payload.getReason() == null ? "quota.changed" : payload.getReason());
        });
    }
}

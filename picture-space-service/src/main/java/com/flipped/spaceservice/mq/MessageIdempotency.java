package com.flipped.spaceservice.mq;

import com.flipped.spaceservice.mapper.ConsumedMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消费幂等登记（阶段 5d）
 *
 * <p>所有 {@code @RabbitListener} 都必须先经过这里，否则「至少一次投递」会变成数据错误 ——
 * 对配额来说就是**同一条消息被消费两次、额度被扣两次**。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MessageIdempotency {

    @Resource
    private ConsumedMessageMapper consumedMessageMapper;

    /**
     * 认领一条消息。
     *
     * @return true = 首次见到，应继续处理；false = 重复投递，应直接返回（= ack）
     */
    public boolean claim(String messageId, String consumer, String messageType) {
        if (messageId == null || messageId.isEmpty()) {
            // 没有 messageId 就没法去重。放行会丢幂等保护，拒绝会让老消息全进死信，
            // 所以记录警告后**放行**，靠日志把问题暴露出来。
            log.warn("消息缺少 messageId，无法做幂等去重（将直接执行），consumer = {}，type = {}",
                    consumer, messageType);
            return true;
        }
        int inserted = consumedMessageMapper.tryConsume(messageId, consumer, messageType);
        if (inserted == 0) {
            log.info("重复消息已被幂等去重丢弃：messageId = {}，consumer = {}，type = {}",
                    messageId, consumer, messageType);
            return false;
        }
        return true;
    }

    /**
     * 撤销认领（业务失败时调用），让这条消息能被重试。
     */
    public void release(String messageId, String consumer) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        try {
            consumedMessageMapper.release(messageId, consumer);
        } catch (Exception e) {
            log.error("撤销消息认领失败，messageId = {}，consumer = {}", messageId, consumer, e);
        }
    }
}

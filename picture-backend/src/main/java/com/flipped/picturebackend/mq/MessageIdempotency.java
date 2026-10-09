package com.flipped.picturebackend.mq;

import com.flipped.picturebackend.mapper.ConsumedMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消费幂等登记（阶段 5a）
 *
 * <p>所有 {@code @RabbitListener} 都必须先经过这里，否则「至少一次投递」会变成数据错误
 * （最典型的就是配额被重复扣减）。
 *
 * <p>用法：
 * <pre>{@code
 * if (!idempotent.claim(message.getMessageId(), QUEUE, type)) {
 *     return;                       // 重复投递，直接返回 = ack
 * }
 * try {
 *     doBusiness(payload);
 *     // 成功：登记保留，重投会被拦住
 * } catch (Exception e) {
 *     idempotent.release(messageId, QUEUE);   // 失败：撤销登记，让它能被重试
 *     throw e;                                 // 交给 DLQ / 重试队列
 * }
 * }</pre>
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
     * @return true = 首次见到，调用方应继续处理；false = 重复投递，调用方应直接返回
     */
    public boolean claim(String messageId, String consumer, String messageType) {
        if (messageId == null || messageId.isEmpty()) {
            // 没有 messageId 就没法去重。放行会丢幂等保护，拒绝会让老消息全进死信，
            // 所以这里记录警告后**放行**，并靠日志把问题暴露出来。
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
            // 撤销失败不抛：它只影响「重试时会不会被误判为重复」，
            // 不应该因为这个把原本的业务失败变成另一种失败
            log.error("撤销消息认领失败，messageId = {}，consumer = {}", messageId, consumer, e);
        }
    }
}

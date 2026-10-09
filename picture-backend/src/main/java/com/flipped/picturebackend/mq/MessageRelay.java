package com.flipped.picturebackend.mq;

import cn.hutool.json.JSONUtil;
import com.flipped.picturebackend.model.entity.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 可靠投递：本地消息表 + 立即尝试 + 定时补投（阶段 5c）
 *
 * <h3>为什么不能「直接在事务里发消息」</h3>
 * 发消息是网络 I/O，放在数据库事务里会把事务拉长（持锁、占连接）；
 * 而放在事务**外面**就会漏掉「业务提交成功但消息没发出去」这个窗口。
 * 本地消息表的办法是：**事务内只写库**（与业务数据同一个本地事务，原子），
 * 消息的发送交给事务提交之后 —— 立即尝试一次，失败就等着定时任务补投。
 *
 * <h3>两个方法的分工</h3>
 * <ul>
 *     <li>{@link #saveInTransaction} —— 必须在业务事务里调用，只落库、不发送；</li>
 *     <li>{@link #publishNow} —— 事务提交后调用（或由定时任务调用），真正发消息。</li>
 * </ul>
 * 这样安排还有一个好处：**发送逻辑只有一份**，定时补投与立即投递走的是同一条路径，
 * 不存在「立即发成功、补投发漏字段」这类分叉。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MessageRelay {

    @Resource
    private LocalMessageRepository repository;

    @Resource
    private MqPublisher mqPublisher;

    /**
     * 单次补投的最大条数，避免一次把 broker 打满
     */
    @Value("${picture.mq.relay.batch-size:100}")
    private int batchSize;

    /**
     * 投递失败的最大重试次数，超过则转人工
     */
    @Value("${picture.mq.relay.max-retry:10}")
    private int maxRetry;

    /**
     * 退避基准（毫秒）：第 n 次失败后等待 base * 2^(n-1)
     */
    @Value("${picture.mq.relay.base-delay-ms:2000}")
    private long baseDelayMs;

    /**
     * 在**业务事务内**登记一条待发送消息。
     *
     * @return 落库后的记录（{@link #publishNow} 需要它的 id 与 messageId）
     */
    public LocalMessage saveInTransaction(String exchange, String routingKey, MqMessage<?> message) {
        LocalMessage record = new LocalMessage();
        record.setMessageId(message.getMessageId());
        record.setMessageType(message.getMessageType());
        record.setExchange(exchange);
        record.setRoutingKey(routingKey);
        record.setPayload(JSONUtil.toJsonStr(message));
        record.setStatus(LocalMessageRepository.STATUS_PENDING);
        record.setRetryCount(0);
        record.setCreateTime(new Date());
        repository.save(record);
        log.info("消息已登记到本地消息表，待投递：messageId = {}，type = {}，routingKey = {}",
                message.getMessageId(), message.getMessageType(), routingKey);
        return record;
    }

    /**
     * 立即尝试投递一条已登记的消息（事务提交后调用）。
     * <p>
     * 失败**不抛异常**：消息已经在本地消息表里，定时任务会补投。
     * 对外抛出只会让业务调用方无辜地失败一次。
     *
     * @return true 表示本次投递成功
     */
    public boolean publishNow(LocalMessage record) {
        if (record == null) {
            return false;
        }
        try {
            mqPublisher.publish(record.getRoutingKey(), JSONUtil.toBean(record.getPayload(), MqMessage.class));
            repository.markSent(record.getId());
            return true;
        } catch (Exception e) {
            // 把**真实原因**记进本地消息表（MqPublisher 刻意不吞异常就是为了这个）：
            // 「Connection refused」和「一句 false」在排查时是天壤之别
            log.warn("立即投递失败（已交给定时补投）：messageId = {}，原因 = {}",
                    record.getMessageId(), e.getMessage());
            repository.markFailed(record, e.getMessage(), maxRetry, baseDelayMs);
        }
        return false;
    }

    /**
     * 扫描并补投所有到点的待投递消息。
     *
     * @return 本次成功投递的条数
     */
    public int relayPending() {
        List<LocalMessage> pending = repository.findPending(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }
        log.info("开始补投本地消息，待投递条数 = {}", pending.size());
        int sent = 0;
        for (LocalMessage record : pending) {
            if (publishNow(record)) {
                sent++;
            }
        }
        log.info("补投完成：成功 = {}，总数 = {}", sent, pending.size());
        return sent;
    }
}

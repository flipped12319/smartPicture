package com.flipped.picturebackend.mq;

import cn.hutool.json.JSONUtil;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.model.entity.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 死信队列重放（阶段 5c）
 *
 * <h3>它补的是哪块</h3>
 * 「延迟重试 → 死信」这条链保证了失败消息**不会丢**，但它们会**静静躺在死信队列里**：
 * 消费端已经重试到上限、不再自动重投，而消息还在。没有重放能力的话，
 * 「失败可重放」这条验收就没有兑现 —— 只能靠人工去管理台一条条点。
 *
 * <h3>关键设计：重放时重新生成 messageId</h3>
 * 死信里的消息带着旧 messageId，而那个 id 很可能已经躺在 {@code consumed_message} 里
 * （消费失败时虽然会 release，但重试耗尽、进程崩溃等场景下可能残留）。
 * 沿用旧 id 会让重放被幂等表当成「重复消息」直接丢掉 —— 表面上重放了，实际什么都没做。
 * 重放是**人工触发的动作**，语义上就是「再来一次」，所以给它一个新的 id。
 *
 * <h3>防止无限循环</h3>
 * 两条路各自有闸：
 * <ul>
 *     <li><b>自动补投</b>：{@code local_message.retryCount} 超过上限后转「待人工」，不再重试；</li>
 *     <li><b>重放</b>：人工触发，且每次调用都带 {@code maxReplay} 上限；
 *         反复失败时消息会再次进入死信队列，堆在那里等人看 —— 不会自己转圈。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class DlqReplayService {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private AmqpAdmin amqpAdmin;

    @Resource
    private MessageRelay messageRelay;

    /**
     * 取消息的超时（毫秒）。队列为空时 {@code receive} 会一直阻塞，
     * 所以先查队列深度，再带超时地取。
     */
    private static final long RECEIVE_TIMEOUT_MS = 300;

    /**
     * 查看某个队列当前堆积多少消息。
     * <p>
     * ⚠️ <b>broker 不可用时返回 -1，绝不抛异常</b>（实测踩过）：
     * 这个方法的调用方之一是 {@code /mq/debug/status} —— 而「broker 挂了」正是最需要看它的时刻。
     * 让它抛异常，等于**在最需要诊断的时候诊断接口先崩了**（返回 50000 系统错误），
     * 把「MQ 不可用」这个明确信息变成了一句没用的系统错误。
     * 所以这里降级为「深度未知」，把连接失败作为信息暴露给调用方。
     *
     * @return 队列深度；-1 表示查不到（broker 不可用）
     */
    public int queueDepth(String queueName) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queueName);
            return info == null ? 0 : info.getMessageCount();
        } catch (Exception e) {
            log.warn("查询队列深度失败（broker 可能不可用）：queue = {}，原因 = {}", queueName, e.getMessage());
            return -1;
        }
    }

    /**
     * 重放某个死信队列里的消息。
     *
     * @param dlqName    死信队列名（只允许认识的死信队列，见 {@link #assertKnownDlq}）
     * @param routingKey 重新投递时用的路由键（该业务的业务路由键，如 picture.index.requested）
     * @param maxReplay  本次最多重放多少条（防止一次点下去把整个队列放出来）
     * @return 实际重放的条数
     */
    public int replay(String dlqName, String routingKey, int maxReplay) {
        assertKnownDlq(dlqName);
        int depth = queueDepth(dlqName);
        if (depth <= 0) {
            log.info("死信队列为空，无需重放：{}", dlqName);
            return 0;
        }
        int limit = Math.min(Math.max(1, maxReplay), depth);
        log.warn("开始重放死信消息：队列 = {}，当前堆积 = {}，本次重放上限 = {}", dlqName, depth, limit);

        int replayed = 0;
        for (int i = 0; i < limit; i++) {
            Message raw = rabbitTemplate.receive(dlqName, RECEIVE_TIMEOUT_MS);
            if (raw == null) {
                break; // 队列已被取空
            }
            MqMessage<?> original = parse(raw);
            if (original == null) {
                // 解析不出来就无法重放。放回队列并停止，避免「取出来又放回去」空转：
                // 留在队列里比丢掉好，同时报错让人来看。
                log.error("死信消息无法解析，已放回队列并停止本次重放，需人工排查：{}",
                        new String(raw.getBody(), StandardCharsets.UTF_8));
                rabbitTemplate.send(MqConfig.DLX_EXCHANGE, dlqName, raw);
                break;
            }

            MqMessage<Object> replayMessage = MqMessage.of(
                    UUID.randomUUID().toString(),
                    original.getMessageType(),
                    (original.getSource() == null ? "unknown" : original.getSource()) + "/dlq-replay",
                    original.getPayload(),
                    null);
            LocalMessage record = messageRelay.saveInTransaction(MqConfig.EXCHANGE, routingKey, replayMessage);
            if (messageRelay.publishNow(record)) {
                replayed++;
            } else {
                // 投不出去也不会丢：本地消息表里是待投递状态，定时任务会继续试
                log.error("重放投递未成功，消息已留在本地消息表等待补投：messageId = {}",
                        replayMessage.getMessageId());
            }
        }
        log.warn("死信重放结束：成功 = {}，本次上限 = {}", replayed, limit);
        return replayed;
    }

    /**
     * 解析死信消息体。JSON 转换失败时返回 null（由调用方决定怎么处理）。
     */
    private MqMessage<?> parse(Message raw) {
        try {
            return JSONUtil.toBean(new String(raw.getBody(), StandardCharsets.UTF_8), MqMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验队列名是不是我们认识的死信队列。
     * <p>
     * 这个接口能从队列里**取走**消息，如果不校验队列名，它就成了「任意队列清空工具」，
     * 误用一次就可能把业务队列里的消息全捞走。
     */
    public void assertKnownDlq(String dlqName) {
        boolean known = MqConfig.INDEX_DLQ.equals(dlqName) || MqConfig.TEST_DLQ.equals(dlqName);
        if (!known) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "未知的死信队列：" + dlqName);
        }
    }
}

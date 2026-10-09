package com.flipped.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.flipped.picturebackend.mapper.LocalMessageMapper;
import com.flipped.picturebackend.model.entity.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 本地消息表的读写（阶段 5c）
 *
 * <p>把「写待投递消息」「查待投递消息」「标记已投递」这些表的操作收在一处，
 * 上层的 {@link MessageRelay} 只关心「什么时候写、什么时候发」。
 *
 * <p><b>为什么状态推进只用「幂等」的更新条件</b>：
 * 定时任务与 afterCommit 的立即投递**可能同时**处理同一条记录（两者并发是正常的，
 * 不是异常）。所以：
 * <ul>
 *     <li>标记已投递时带上 {@code status = 0} 条件，谁先成功谁生效，后到的更新影响 0 行；</li>
 *     <li>投递本身可能因此重复发一次消息 —— 这正是幂等表要兜住的场景。</li>
 * </ul>
 * 换句话说：这里**宁可多发一次，也不能漏发**，重复由消费端消化。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class LocalMessageRepository {

    /**
     * 0-待投递
     */
    public static final int STATUS_PENDING = 0;
    /**
     * 1-已投递（已交给 broker，不代表消费成功）
     */
    public static final int STATUS_SENT = 1;
    /**
     * 2-已确认（消费端处理成功）
     */
    public static final int STATUS_CONFIRMED = 2;
    /**
     * 3-超过重试上限，需要人工介入
     */
    public static final int STATUS_GIVE_UP = 3;

    @Resource
    private LocalMessageMapper localMessageMapper;

    /**
     * 落库一条待投递消息。
     * <p>
     * <b>调用方必须在业务事务内调用</b>：这样「业务数据」与「待发消息」一起提交，
     * 不会出现「业务成功但消息没了」。
     *
     * @return 落库后的实体（带 id 与 messageId）
     */
    public LocalMessage save(LocalMessage message) {
        if (message.getStatus() == null) {
            message.setStatus(STATUS_PENDING);
        }
        if (message.getRetryCount() == null) {
            message.setRetryCount(0);
        }
        message.setCreateTime(new Date());
        message.setUpdateTime(new Date());
        localMessageMapper.insert(message);
        return message;
    }

    /**
     * 查一批待投递的消息（含「到点了才投」的退避记录）。
     *
     * @param limit 单次上限，避免一次扫太多把内存和 broker 打满
     */
    public List<LocalMessage> findPending(int limit) {
        Date now = new Date();
        return localMessageMapper.selectList(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getStatus, STATUS_PENDING)
                // nextRetryAt 为空表示「从未尝试过」，可以立刻投
                .and(w -> w.isNull(LocalMessage::getNextRetryAt).or().le(LocalMessage::getNextRetryAt, now))
                .orderByAsc(LocalMessage::getId)
                .last("limit " + Math.max(1, limit)));
    }

    /**
     * 标记「已投递给 broker」。带 {@code status = 0} 条件，重复调用无副作用。
     *
     * @return true 表示本次调用真正推进了状态（false = 别人已经推进过）
     */
    public boolean markSent(Long id) {
        int updated = localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                .eq(LocalMessage::getId, id)
                .eq(LocalMessage::getStatus, STATUS_PENDING)
                .set(LocalMessage::getStatus, STATUS_SENT)
                .set(LocalMessage::getRetryCount, 0)
                .set(LocalMessage::getLastError, null)
                .set(LocalMessage::getNextRetryAt, null)
                .set(LocalMessage::getUpdateTime, new Date()));
        return updated > 0;
    }

    /**
     * 投递失败：记录原因并按指数退避安排下次重试；超过上限则置为待人工。
     * <p>
     * 退避的意义：MQ 长时间不可用时，不要每秒都去重试同一条（把日志和 CPU 刷满），
     * 而是逐步拉长间隔。
     */
    public void markFailed(LocalMessage message, String error, int maxRetry, long baseDelayMs) {
        int retry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        LambdaUpdateWrapper<LocalMessage> update = new LambdaUpdateWrapper<LocalMessage>()
                .eq(LocalMessage::getId, message.getId())
                .set(LocalMessage::getRetryCount, retry)
                .set(LocalMessage::getLastError, error == null ? null : error.substring(0, Math.min(490, error.length())))
                .set(LocalMessage::getUpdateTime, new Date());
        if (retry >= maxRetry) {
            update.set(LocalMessage::getStatus, STATUS_GIVE_UP);
            log.error("本地消息投递超过上限，转为待人工处理：messageId = {}，type = {}，retry = {}",
                    message.getMessageId(), message.getMessageType(), retry);
        } else {
            // 指数退避：base * 2^(retry-1)，上限 5 分钟
            long delay = Math.min(baseDelayMs * (1L << Math.min(retry - 1, 10)), 5 * 60 * 1000L);
            update.set(LocalMessage::getNextRetryAt, new Date(System.currentTimeMillis() + delay));
        }
        localMessageMapper.update(null, update);
    }

    /**
     * 统计各状态数量，给对账与自检用。
     */
    public long countByStatus(int status) {
        return localMessageMapper.selectCount(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getStatus, status));
    }
}

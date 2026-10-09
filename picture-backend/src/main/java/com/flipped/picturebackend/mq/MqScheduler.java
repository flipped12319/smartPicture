package com.flipped.picturebackend.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * MQ 可靠性相关的定时任务（阶段 5c）
 *
 * <p>本项目**第一个** {@code @Scheduled}，所以这里把约定写清楚：
 * <ul>
 *     <li>所有任务都挂 {@code picture.mq.enabled}：MQ 关闭时这些 bean 不存在，任务自然不跑；</li>
 *     <li>每个任务都自己 try-catch 兜住异常 —— 定时任务抛异常只会打断本次执行并打日志，
 *         但如果异常类型意外（比如 Error）可能影响后续调度，兜住更稳；</li>
 *     <li>间隔都走配置，便于在真实环境按量级调整（默认值按「单机开发」取的）。</li>
 * </ul>
 *
 * <p>用固定延迟（{@code fixedDelay}）而不是固定频率（{@code fixedRate}）：
 * 固定频率在任务耗时超过间隔时会排队堆积，而这几个任务本身可能要扫表。
 */
@Component
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class MqScheduler {

    @Resource
    private MessageRelay messageRelay;

    @Resource
    private IndexReconcileService indexReconcileService;

    /**
     * 补投本地消息表里未发出的消息：<b>这是「MQ 挂了消息也不丢」的兑现处</b>。
     * <p>
     * 间隔取得比较短（3 秒），因为开发/演示时希望 MQ 一恢复就能看到补投发生；
     * 生产可以放到 10~30 秒，配合退避已经足够。
     */
    @Scheduled(fixedDelayString = "${picture.mq.relay.interval-ms:3000}",
            initialDelayString = "${picture.mq.relay.initial-delay-ms:15000}")
    public void relayPendingMessages() {
        try {
            int sent = messageRelay.relayPending();
            if (sent > 0) {
                log.info("定时补投完成：本次成功投递 {} 条", sent);
            }
        } catch (Exception e) {
            log.error("定时补投执行失败（下个周期会再试）", e);
        }
    }

    /**
     * 对账：找出「审核通过但其实没有索引」的图片并补建索引。
     * <p>
     * 为什么还需要它：本地消息表能保证「消息不丢」，但保证不了「消息从头就没产生」
     * —— 比如图片是在 MQ 功能上线**之前**过审的、或者某次改动漏了触发点。
     * 对账是最后一道网：**以数据库的期望状态为准**去修正，而不是相信事件一定被发出过。
     * <p>
     * 间隔取长（默认 10 分钟）：它不是实时路径，只是兜底。
     */
    @Scheduled(fixedDelayString = "${picture.mq.reconcile.interval-ms:600000}",
            initialDelayString = "${picture.mq.reconcile.initial-delay-ms:60000}")
    public void reconcileIndexStatus() {
        try {
            int enqueued = indexReconcileService.reconcileMissingIndex();
            if (enqueued > 0) {
                log.info("索引对账完成：补建索引 {} 张", enqueued);
            }
        } catch (Exception e) {
            log.error("索引对账执行失败（下个周期会再试）", e);
        }
    }
}

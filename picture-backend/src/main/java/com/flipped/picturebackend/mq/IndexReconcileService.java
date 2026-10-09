package com.flipped.picturebackend.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flipped.picturebackend.mapper.PictureMapper;
import com.flipped.picturebackend.model.entity.LocalMessage;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.enums.PictureIndexStatusEnum;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 索引对账（阶段 5c）
 *
 * <h3>为什么「有本地消息表」还需要对账</h3>
 * 本地消息表保证的是「**已经产生的消息**不丢」。它保证不了「消息从头就没产生」：
 * <ul>
 *     <li>图片是在 MQ 功能上线**之前**过审的，当时根本没有触发点；</li>
 *     <li>某次改动漏掉了一个触发点（本项目索引有 3 个触发位置，很容易漏）；</li>
 *     <li>业务事务在写消息之前就异常退出了。</li>
 * </ul>
 * 这几类问题**事件驱动架构天生看不到** —— 没有事件，就没有任何东西会通知你。
 * 所以需要一次「以数据库的期望状态为准」的反向核对：
 * **「审核通过的图片，按定义就应该有索引」**，凡是没索引的，补一条索引消息。
 *
 * <h3>对账的判定条件（刻意保守）</h3>
 * 只捞 {@code indexStatus = 0（未索引）} 的图片，**不碰 {@code indexStatus = 2（索引失败）}**。
 * 原因：失败的图片是「已经尝试过、消费端已重试到死信」的，它需要的是 DLQ 重放（人工/半自动），
 * 而不是每隔 10 分钟被自动重投一次 —— 否则模型服务长时间不可用时会白白刷出大量重复任务。
 * 这条取舍写在文档里，避免以后有人「顺手」把它加进来。
 */
@Service
@ConditionalOnProperty(name = "picture.mq.enabled", havingValue = "true")
@Slf4j
public class IndexReconcileService {

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private MessageRelay messageRelay;

    /**
     * 单次对账最多补多少张，避免一次性刷出大量任务
     */
    @Value("${picture.mq.reconcile.batch-size:50}")
    private int batchSize;

    /**
     * 找出「审核通过但从未索引」的图片，各补一条索引消息。
     *
     * @return 本次补建的消息条数
     */
    public int reconcileMissingIndex() {
        List<Picture> orphans = pictureMapper.selectList(new LambdaQueryWrapper<Picture>()
                .select(Picture::getId, Picture::getSpaceId)
                .eq(Picture::getReviewStatus, PictureReviewStatusEnum.PASS.getValue())
                .eq(Picture::getIndexStatus, PictureIndexStatusEnum.PENDING.getValue())
                .orderByAsc(Picture::getId)
                // @TableLogic 会自动加 isDelete = 0，这里不用再写
                .last("limit " + Math.max(1, batchSize)));
        if (orphans.isEmpty()) {
            return 0;
        }
        log.warn("索引对账发现 {} 张「已过审但未索引」的图片，补建索引消息（前几个 id：{}）",
                orphans.size(),
                // 项目是 Java 11：Stream.toList() 是 16+ 的 API，这里必须用 collect
                orphans.stream().limit(5).map(Picture::getId).collect(Collectors.toList()));
        int enqueued = 0;
        for (Picture picture : orphans) {
            PictureIndexPayload payload = new PictureIndexPayload(picture.getId(), true);
            MqMessage<PictureIndexPayload> message =
                    MqMessage.of(MqConfig.INDEX_ROUTING_KEY, "reconcile", payload);
            LocalMessage record = messageRelay.saveInTransaction(
                    MqConfig.EXCHANGE, MqConfig.INDEX_ROUTING_KEY, message);
            messageRelay.publishNow(record);
            enqueued++;
        }
        return enqueued;
    }
}

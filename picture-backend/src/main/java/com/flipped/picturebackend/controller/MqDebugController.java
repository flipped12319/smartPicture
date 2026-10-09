package com.flipped.picturebackend.controller;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.mq.DlqReplayService;
import com.flipped.picturebackend.mq.IndexReconcileService;
import com.flipped.picturebackend.mq.LocalMessageRepository;
import com.flipped.picturebackend.mq.MqConfig;
import com.flipped.picturebackend.mq.MqMessage;
import com.flipped.picturebackend.mq.MqPublisher;
import com.flipped.picturebackend.mq.MessageRelay;
import com.flipped.picturebackend.mq.PictureIndexPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息队列自检接口（阶段 5a，默认关闭）
 *
 * <p>只有 {@code picture.mq.debug-enabled=true} 时才注册 —— 它是**开发期工具**，
 * 不能留在生产环境里（能任意投递消息到业务交换机）。
 *
 * <p>存在的意义：幂等去重这种东西，光看代码看不出问题，必须能**真的把同一条消息发两遍**。
 * 用管理台的 "Publish message" 做不到指定 messageId，curl 发 AMQP 又太麻烦。
 */
@RestController
@RequestMapping("/mq/debug")
@ConditionalOnProperty(name = "picture.mq.debug-enabled", havingValue = "true")
@Slf4j
public class MqDebugController {

    @Resource
    private MqPublisher mqPublisher;

    @Resource
    private com.flipped.picturebackend.service.SpaceService spaceService;

    @Resource
    private LocalMessageRepository localMessageRepository;

    @Resource
    private DlqReplayService dlqReplayService;

    @Resource
    private IndexReconcileService indexReconcileService;

    /**
     * 用 ObjectProvider 取：本控制器与 MQ 同生共死（都挂 debug-enabled），
     * 但保持与业务侧一致的取法，避免「哪个条件下必须有哪个 bean」这类隐式耦合。
     */
    @Resource
    private ObjectProvider<MessageRelay> messageRelayProvider;

    /**
     * 查看 MQ 可靠性状态（阶段 5c）：本地消息表各状态数量 + 关键队列深度。
     * <p>
     * 排查「消息到底发出去没有」时，这是第一个该看的接口：
     * 本地消息表里还有 pending，说明消息**没丢**、只是还没投出去（等补投或 MQ 恢复）；
     * 死信队列有堆积，说明消费端处理失败、需要重放。
     */
    @GetMapping("/status")
    public BaseResponse<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localMessagePending", messageRelayProvider.getIfAvailable() == null ? null
                : localMessageRepository.countByStatus(LocalMessageRepository.STATUS_PENDING));
        result.put("localMessageSent", localMessageRepository.countByStatus(LocalMessageRepository.STATUS_SENT));
        result.put("localMessageGiveUp", localMessageRepository.countByStatus(LocalMessageRepository.STATUS_GIVE_UP));
        result.put("indexQueueDepth", dlqReplayService.queueDepth(MqConfig.INDEX_QUEUE));
        result.put("indexDlqDepth", dlqReplayService.queueDepth(MqConfig.INDEX_DLQ));
        result.put("testDlqDepth", dlqReplayService.queueDepth(MqConfig.TEST_DLQ));
        return ResultUtils.success(result);
    }

    /**
     * 重放死信队列（阶段 5c）：把失败的消息重新投回业务队列。
     *
     * @param dlq        死信队列名，只接受认识的死信队列（默认索引的）
     * @param maxReplay  本次最多重放多少条
     */
    @PostMapping("/replay-dlq")
    public BaseResponse<Integer> replayDlq(@RequestParam(required = false) String dlq,
                                           @RequestParam(required = false, defaultValue = "10") Integer maxReplay) {
        String target = (dlq == null || dlq.isEmpty()) ? MqConfig.INDEX_DLQ : dlq;
        String routingKey = MqConfig.INDEX_DLQ.equals(target)
                ? MqConfig.INDEX_ROUTING_KEY : MqConfig.TEST_ROUTING_KEY;
        int replayed = dlqReplayService.replay(target, routingKey, maxReplay == null ? 10 : maxReplay);
        return ResultUtils.success(replayed);
    }

    /**
     * 手动触发一次索引对账（阶段 5c），便于验证而不用等定时任务。
     *
     * @return 本次补建索引消息的条数
     */
    @PostMapping("/reconcile")
    public BaseResponse<Integer> reconcile() {
        return ResultUtils.success(indexReconcileService.reconcileMissingIndex());
    }

    /**
     * 发一条额度变更事件（阶段 5d-b）：走「本地消息表 + MQ」这条路，
     * 由 **space-service** 幂等消费后改额度。
     * <p>
     * 为什么需要它：删除路径的事件化很难在自动化里触发（要真的删一张有 COS 文件的图片），
     * 而「消息能不能跨服务送达并被消费」与「谁触发的」无关，所以这里直接投一条。
     */
    @PostMapping("/publish-quota-changed")
    public BaseResponse<Boolean> publishQuotaChanged(@RequestParam Long spaceId,
                                                     @RequestParam(defaultValue = "0") Long sizeDelta,
                                                     @RequestParam(defaultValue = "0") Long countDelta,
                                                     @RequestParam(required = false, defaultValue = "debug") String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "spaceId 不合法");
        // 复用业务侧的同一个出口（本地消息表 + 立即尝试投递），保证与真实路径完全一致
        spaceService.updateSpaceQuota(spaceId, sizeDelta, countDelta, reason);
        return ResultUtils.success(true);
    }

    /**
     * 发一条索引请求（走 5b 的索引消费者）。
     * <p>
     * 为什么需要它：索引的真实触发需要**上传一张真图片到 COS**（还要等模型跑几十秒），
     * 不适合放进自动化验证。而索引消费者的逻辑（幂等、ack、失败处理）与「消息从哪来」无关，
     * 所以这里直接投一条索引消息，就能把消费者侧验证干净。
     *
     * @param pictureId 必须是**真实存在**的图片 id；传不存在的 id 是有效用例 ——
     *                  消费者应当「跳过并 ack」，消息不该堆在队列里
     */
    @PostMapping("/publish-index")
    public BaseResponse<Boolean> publishIndex(@RequestParam Long pictureId,
                                              @RequestParam(required = false) String messageId,
                                              @RequestParam(required = false, defaultValue = "false") Boolean fillBlankFields) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR, "pictureId 不合法");
        PictureIndexPayload payload = new PictureIndexPayload(pictureId, fillBlankFields);
        MqMessage<PictureIndexPayload> message = (messageId == null || messageId.isEmpty())
                ? MqMessage.of(MqConfig.INDEX_ROUTING_KEY, "picture-backend", payload)
                : MqMessage.of(messageId, MqConfig.INDEX_ROUTING_KEY, "picture-backend", payload, null);
        log.info("自检投递索引请求：messageId = {}，pictureId = {}", message.getMessageId(), pictureId);
        // publish 现在失败即抛（让调用方能拿到真实原因），所以走到这里就是成功
        mqPublisher.publish(MqConfig.INDEX_ROUTING_KEY, message);
        return ResultUtils.success(true);
    }

    /**
     * 发一条测试消息。
     *
     * <p>路由键可传：默认发给单体自己的自检队列；用 {@code picture.space.test} 可以发给
     * **space-service** 的自检队列（阶段 5d 的第一条消费链路，用来单独验证它的消费地基）。
     *
     * @param messageId  可选。**传相同值两次即可验证幂等**：消费端第二次会丢弃
     * @param text       消息体内容，仅用于肉眼确认
     * @param routingKey 目标路由键，默认单体自检队列
     */
    @PostMapping("/publish-test")
    public BaseResponse<Boolean> publishTest(@RequestParam(required = false) String messageId,
                                             @RequestParam(required = false, defaultValue = "hello") String text,
                                             @RequestParam(required = false) String routingKey) {
        ThrowUtils.throwIf(text == null || text.isEmpty(), ErrorCode.PARAMS_ERROR, "text 不能为空");
        String key = (routingKey == null || routingKey.isEmpty()) ? MqConfig.TEST_ROUTING_KEY : routingKey;
        MqMessage<String> message = (messageId == null || messageId.isEmpty())
                ? MqMessage.of(key, "picture-backend", text)
                : MqMessage.of(messageId, key, "picture-backend", text, null);
        log.info("自检投递：messageId = {}，routingKey = {}，text = {}", message.getMessageId(), key, text);
        mqPublisher.publish(key, message);
        return ResultUtils.success(true);
    }
}

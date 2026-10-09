package com.flipped.picturebackend.manager.ws;

import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;

/**
 * 生产者：协同编辑消息进入系统的唯一入口
 * <p>
 * 由 WebSocket 的网络 IO 线程调用。这里只负责把消息按顺序放进环形队列，不做任何业务处理，
 * 这样接收线程能立刻返回，不会被业务处理（状态流转、校验、广播）拖慢。
 */
@Slf4j
@Component
public class EditMessageProducer {

    @Resource
    private EditDisruptorManager editDisruptorManager;

    /**
     * 投递一条消息
     *
     * @param session     消息来自哪个连接
     * @param messageType 服务端主动产生的消息类型（INFO / LEAVE）；
     *                    传 null 表示是客户端报文，需要分发器解析
     * @param rawMessage  客户端原始报文，服务端主动产生的消息传 null
     */
    public void publish(WebSocketSession session, PictureEditMessageTypeEnum messageType, String rawMessage) {
        RingBuffer<EditEvent> ringBuffer = editDisruptorManager.getRingBuffer();
        if (ringBuffer == null) {
            log.warn("协同编辑队列尚未就绪，消息被丢弃：sessionId = {}",
                    session == null ? null : session.getId());
            return;
        }
        // 队列满时会在这里等待，属于正常的背压，换取的是消息不丢失
        long sequence = ringBuffer.next();
        try {
            ringBuffer.get(sequence).reset(session, messageType, rawMessage);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
}

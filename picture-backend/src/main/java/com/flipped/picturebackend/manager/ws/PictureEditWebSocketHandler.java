package com.flipped.picturebackend.manager.ws;

import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;

/**
 * 协同编辑的 WebSocket 端点
 * <p>
 * 这里只做「收」和「投递」两件事，所有业务处理都交给 Disruptor 的消费者线程，
 * 因此网络 IO 线程不会被业务逻辑占住。
 * <p>
 * 连接建立时会由服务端主动产生一条 INFO 消息（而不是在这里直接发消息），
 * 保证「消息的发送」全部发生在消费者线程上，避免多线程同时往一个连接写数据。
 */
@Slf4j
@Component
public class PictureEditWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private EditMessageProducer editMessageProducer;

    /**
     * 1. INFO：连接建立，用户加入编辑
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        editMessageProducer.publish(session, PictureEditMessageTypeEnum.INFO, null);
    }

    /**
     * 收到客户端消息：解析与业务处理都不在这里做，只入队
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        editMessageProducer.publish(session, null, message.getPayload());
    }

    /**
     * 5. LEAVE：连接关闭，离开编辑
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        editMessageProducer.publish(session, PictureEditMessageTypeEnum.LEAVE, null);
    }

    /**
     * 传输异常：同样按离开处理，避免房间里残留死连接
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("协同编辑连接传输异常，sessionId = {}", session.getId(), exception);
        editMessageProducer.publish(session, PictureEditMessageTypeEnum.LEAVE, null);
    }
}

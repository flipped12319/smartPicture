package com.flipped.picturebackend.manager.ws;

import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消费者：流水线的第二阶段，真正处理消息
 * <p>
 * 因为只有这一个线程在消费，同一张图片的消息天然按顺序处理，
 * 房间状态不会出现并发写入；而 WebSocket 的接收线程早已返回，不会被打扰。
 */
@Slf4j
@Component
public class EditMessageConsumer implements EventHandler<EditEvent> {

    @Resource
    private EditRoomManager editRoomManager;

    @Override
    public void onEvent(EditEvent event, long sequence, boolean endOfBatch) {
        // 分发阶段发现的问题，统一以 ERROR 消息回给发送方（第 6 类消息）
        if (event.isInvalid()) {
            editRoomManager.sendError(event.getRoom(), event.getSession(), event.getErrorMessage());
            return;
        }
        EditRoom room = event.getRoom();
        PictureEditMessageTypeEnum type = event.getMessageType();
        if (room == null || type == null) {
            log.warn("协同编辑事件缺少必要上下文，已忽略：sessionId = {}", event.getSessionId());
            return;
        }
        switch (type) {
            case INFO:
                editRoomManager.handleJoin(room, event);
                break;
            case START:
                editRoomManager.handleStartEditing(room, event);
                break;
            case OPERATION:
                editRoomManager.handleOperation(room, event);
                break;
            case EXIT:
                editRoomManager.handleExitEditing(room, event);
                break;
            case LEAVE:
                editRoomManager.handleLeave(room, event);
                break;
            default:
                editRoomManager.sendError(room, event.getSession(), "无法处理的消息类型：" + type.getText());
                break;
        }
    }
}

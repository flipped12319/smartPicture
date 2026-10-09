package com.flipped.picturebackend.manager.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipped.picturebackend.model.dto.ws.PictureEditResponseMessage;
import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import com.flipped.picturebackend.model.vo.PictureEditParticipantVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协同编辑的房间管理：维护「图片 -> 房间」的映射，并执行各类消息对应的状态流转与广播。
 * <p>
 * 所有方法都由 Disruptor 的消费者线程串行调用，因此同一张图片的消息天然是按顺序处理的，
 * 房间状态不会出现并发写入。
 */
@Slf4j
@Component
public class EditRoomManager {

    /**
     * 单次发送的时间上限（毫秒）
     */
    private static final int SEND_TIME_LIMIT_MS = 5000;

    /**
     * 单个连接的发送缓冲上限，客户端消费不过来时直接断开，避免缓冲无限增长
     */
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    /**
     * 图片 id -> 编辑房间（ConcurrentHashMap 保证并发安全）
     */
    private final Map<Long, EditRoom> rooms = new ConcurrentHashMap<>();

    /**
     * 报文序列化用的 ObjectMapper（Spring 容器里的那一个）
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 序列化广播报文
     * <p>
     * 这里必须用 Spring 容器里的 ObjectMapper，而不是 Hutool 的 JSONUtil：
     * {@link com.flipped.picturebackend.config.JsonConfig} 把 Long 注册成了
     * ToStringSerializer（雪花 id 有 19 位，超出 JS 安全整数上限 2^53，
     * 直接当 JSON 数字下发会被浏览器四舍五入），Hutool 不认识这个配置，
     * 会把 id 当成数字发出去，导致前端拿到的 userId 与登录用户 id 对不上。
     * 复用同一套 ObjectMapper，可保证 WebSocket 报文与 REST 报文的 Long 表现一致。
     *
     * @return 序列化结果；失败时返回 null（调用方跳过本次发送）
     */
    private String toJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("协同编辑报文序列化失败", e);
            return null;
        }
    }

    /**
     * 获取房间，不存在则创建
     */
    public EditRoom getOrCreateRoom(Long pictureId) {
        return rooms.computeIfAbsent(pictureId, EditRoom::new);
    }

    // ==================== 六类消息的处理 ====================

    /**
     * 1. INFO：用户建立连接，加入编辑
     */
    public void handleJoin(EditRoom room, EditEvent event) {
        String sessionId = event.getSessionId();
        PictureEditParticipantVO participant = resolveParticipant(room, event);
        boolean isNew = room.getSession(sessionId) == null;
        if (isNew) {
            room.join(sessionId, decorate(event.getSession()), participant);
        }
        room.refreshEditingFlag();
        String text = participant.getUserName() + (isNew ? " 加入了编辑" : " 已在编辑中");
        broadcast(room, buildMessage(PictureEditMessageTypeEnum.INFO, text, participant, room, null), null);
        // 有人正在编辑时，把已有的编辑动作私下发给新加入的人，让他的画布直接追平，不用从头看
        List<String> operations = room.snapshotOperations();
        if (!operations.isEmpty()) {
            PictureEditResponseMessage syncMessage =
                    buildMessage(PictureEditMessageTypeEnum.INFO, "已同步当前的编辑状态", participant, room, null);
            syncMessage.setOperationHistory(operations);
            sendToSession(room, sessionId, syncMessage);
        }
        log.info("协同编辑：用户 {}({}) 加入图片 {} 的编辑，当前在线 {} 人",
                participant.getUserName(), event.getUserId(), room.getPictureId(), room.listParticipants().size());
    }

    /**
     * 2. START：开始编辑，抢占编辑权（同时只允许一个人编辑）
     */
    public void handleStartEditing(EditRoom room, EditEvent event) {
        String sessionId = event.getSessionId();
        PictureEditParticipantVO participant = resolveParticipant(room, event);
        requiredJoined(room, event);
        boolean acquired = room.tryAcquireEditing(sessionId);
        room.refreshEditingFlag();
        if (acquired) {
            // 新一轮编辑从当前图片状态开始，清掉上一轮的操作记录
            room.clearOperations();
            broadcast(room,
                    buildMessage(PictureEditMessageTypeEnum.START,
                            participant.getUserName() + " 开始编辑", participant, room, null),
                    null);
            log.info("协同编辑：用户 {}({}) 开始编辑图片 {}",
                    participant.getUserName(), event.getUserId(), room.getPictureId());
        } else {
            // 已被别人占用：只回给发起者，并告诉他当前是谁在编辑
            EditRoom.EditSession editor = room.getEditorSession();
            String editorName = editor == null ? "其他人" : editor.getParticipant().getUserName();
            sendToSession(room, sessionId,
                    buildMessage(PictureEditMessageTypeEnum.START,
                            "图片正在被 " + editorName + " 编辑，请稍后再试", participant, room, null));
        }
    }

    /**
     * 3. OPERATION：编辑操作，广播给房间内的其他用户
     */
    public void handleOperation(EditRoom room, EditEvent event) {
        String sessionId = event.getSessionId();
        EditRoom.EditSession editor = room.getEditorSession();
        if (editor == null || !sessionId.equals(editor.getSessionId())) {
            sendError(room, event.getSession(), "当前不是你处于编辑状态，无法提交编辑操作");
            return;
        }
        PictureEditParticipantVO participant = resolveParticipant(room, event);
        // 记下本次编辑动作：后加入的人据此把画布追到当前状态
        room.recordOperation(event.getPayload());
        // 自己已经本地生效，不需要回声，所以排除自己
        broadcast(room,
                buildMessage(PictureEditMessageTypeEnum.OPERATION,
                        participant.getUserName() + " 调整了图片", participant, room, event.getPayload()),
                sessionId);
    }

    /**
     * 4. EXIT：用户主动退出编辑状态
     */
    public void handleExitEditing(EditRoom room, EditEvent event) {
        String sessionId = event.getSessionId();
        boolean released = room.releaseEditing(sessionId);
        room.refreshEditingFlag();
        if (!released) {
            // 本来就不是编辑者，退出时无事发生，不打扰其他人
            return;
        }
        PictureEditParticipantVO participant = resolveParticipant(room, event);
        broadcast(room,
                buildMessage(PictureEditMessageTypeEnum.EXIT,
                        participant.getUserName() + " 退出了编辑", participant, room, null),
                null);
        log.info("协同编辑：用户 {}({}) 退出编辑图片 {}",
                participant.getUserName(), event.getUserId(), room.getPictureId());
    }

    /**
     * 5. LEAVE：用户断开连接，离开编辑
     */
    public void handleLeave(EditRoom room, EditEvent event) {
        String sessionId = event.getSessionId();
        EditRoom.EditSession removed = room.leave(sessionId);
        if (removed == null) {
            // 连接没成功加入过房间（例如握手后立刻断开），直接清理即可
            removeRoomIfEmpty(room);
            return;
        }
        boolean wasEditing = Boolean.TRUE.equals(removed.getParticipant().getEditing());
        room.refreshEditingFlag();
        PictureEditParticipantVO participant = removed.getParticipant();
        String text = participant.getUserName() + (wasEditing
                ? " 离开了编辑，已释放编辑状态"
                : " 离开了编辑");
        broadcast(room, buildMessage(PictureEditMessageTypeEnum.LEAVE, text, participant, room, null), null);
        log.info("协同编辑：用户 {}({}) 离开图片 {} 的编辑，剩余 {} 人",
                participant.getUserName(), event.getUserId(), room.getPictureId(), room.listParticipants().size());
        removeRoomIfEmpty(room);
    }

    /**
     * 6. ERROR：错误消息只回给发送方
     *
     * @param room 发送方所在的房间，用于复用包装过的会话；允许为 null
     */
    public void sendError(EditRoom room, WebSocketSession session, String errorMessage) {
        if (session == null) {
            return;
        }
        PictureEditResponseMessage message = new PictureEditResponseMessage();
        message.setType(PictureEditMessageTypeEnum.ERROR.getValue());
        message.setMessage(errorMessage);
        WebSocketSession target = session;
        if (room != null) {
            EditRoom.EditSession editSession = room.getSession(session.getId());
            if (editSession != null) {
                target = editSession.getSession();
            }
        }
        doSend(target, toJson(message), session.getId());
    }

    // ==================== 内部工具 ====================

    /**
     * 取出参与者信息；连接还没加入房间时，用连接属性里的信息兜底
     */
    private PictureEditParticipantVO resolveParticipant(EditRoom room, EditEvent event) {
        EditRoom.EditSession editSession = room.getSession(event.getSessionId());
        if (editSession != null) {
            return editSession.getParticipant();
        }
        return buildParticipant(event);
    }

    /**
     * 从连接属性中取出握手阶段解析好的参与者信息
     */
    private PictureEditParticipantVO buildParticipant(EditEvent event) {
        PictureEditParticipantVO participant = new PictureEditParticipantVO();
        participant.setUserId(event.getUserId());
        participant.setEditing(false);
        Object attribute = event.getSession() == null
                ? null
                : event.getSession().getAttributes().get(PictureEditHandshakeInterceptor.ATTR_PARTICIPANT);
        if (attribute instanceof PictureEditParticipantVO) {
            PictureEditParticipantVO fromHandshake = (PictureEditParticipantVO) attribute;
            participant.setUserName(fromHandshake.getUserName());
            participant.setUserAvatar(fromHandshake.getUserAvatar());
        }
        if (participant.getUserName() == null) {
            participant.setUserName("用户" + event.getUserId());
        }
        return participant;
    }

    /**
     * 防御性处理：消息来自还没加入房间的连接时，先补一次加入
     */
    private void requiredJoined(EditRoom room, EditEvent event) {
        if (room.getSession(event.getSessionId()) == null) {
            handleJoin(room, event);
        }
    }

    /**
     * 房间空了就移除，避免 rooms 无限增长
     */
    private void removeRoomIfEmpty(EditRoom room) {
        if (room.isEmpty()) {
            rooms.remove(room.getPictureId(), room);
        }
    }

    /**
     * 组装广播报文（每次都会带上最新的编辑者与参与者列表，前端据此渲染状态）
     */
    private PictureEditResponseMessage buildMessage(PictureEditMessageTypeEnum type, String text,
                                                    PictureEditParticipantVO user, EditRoom room,
                                                    String payload) {
        EditRoom.EditSession editor = room.getEditorSession();
        PictureEditResponseMessage message = new PictureEditResponseMessage();
        message.setType(type.getValue());
        message.setMessage(text);
        message.setUser(user);
        message.setEditorUserId(room.getEditorUserId());
        message.setEditorUserName(editor == null ? null : editor.getParticipant().getUserName());
        message.setParticipants(room.listParticipants());
        message.setPayload(payload);
        return message;
    }

    /**
     * 广播给房间内所有人
     *
     * @param excludeSessionId 需要排除的连接（例如操作发起者自己）
     */
    private void broadcast(EditRoom room, PictureEditResponseMessage message, String excludeSessionId) {
        String json = toJson(message);
        if (json == null) {
            return;
        }
        for (EditRoom.EditSession editSession : room.listSessions()) {
            if (excludeSessionId != null && excludeSessionId.equals(editSession.getSessionId())) {
                continue;
            }
            doSend(editSession.getSession(), json, editSession.getSessionId());
        }
    }

    /**
     * 只发给房间内的某个连接
     */
    private void sendToSession(EditRoom room, String sessionId, PictureEditResponseMessage message) {
        EditRoom.EditSession editSession = room.getSession(sessionId);
        if (editSession == null) {
            return;
        }
        doSend(editSession.getSession(), toJson(message), sessionId);
    }

    /**
     * 发送消息
     * <p>
     * 注意：Spring 的 WebSocketSession 没有 JSR-356 的 getAsyncRemote()，统一用 sendMessage。
     * 连接在加入房间时被 ConcurrentWebSocketSessionDecorator 包过：当前一条消息还没写完时，
     * 它会把新消息先缓冲起来由专门的回调发送，因此既不会并发写同一个连接，
     * 也不会让消费者线程长时间阻塞在网络写上。
     */
    private void doSend(WebSocketSession session, String json, String sessionId) {
        if (session == null || !session.isOpen() || json == null) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("向协同编辑连接发送消息失败，sessionId = {}", sessionId, e);
        }
    }

    /**
     * 包装连接：让同一连接的多次发送串行化，并在客户端消费不过来时断开连接
     */
    private WebSocketSession decorate(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        return new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }
}

package com.flipped.picturebackend.manager.ws;

import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.socket.WebSocketSession;

/**
 * Disruptor 环形队列中的事件
 * <p>
 * 注意：Disruptor 会复用事件对象，因此每次发布都必须在 {@link #reset} 里把所有字段重新赋值，
 * 不能依赖上一次的残留值。
 */
@Getter
public class EditEvent {

    /**
     * 事件关联的连接
     */
    private WebSocketSession session;

    /**
     * 连接 id
     */
    private String sessionId;

    /**
     * 图片 id（由分发器从连接属性中取出）
     */
    @Setter
    private Long pictureId;

    /**
     * 用户 id（由分发器从连接属性中取出）
     */
    @Setter
    private Long userId;

    /**
     * 原始报文：解析放在分发器阶段完成，网络接收线程只负责入队
     */
    private String rawMessage;

    /**
     * 消息类型；为 null 表示需要由分发器从原始报文里解析
     */
    @Setter
    private PictureEditMessageTypeEnum messageType;

    /**
     * 编辑操作内容
     */
    @Setter
    private String payload;

    /**
     * 分发器路由出的编辑房间
     */
    @Setter
    private EditRoom room;

    /**
     * 报文非法时的原因，非空表示这条消息是错误消息
     */
    @Setter
    private String errorMessage;

    /**
     * 重新初始化事件（Disruptor 复用对象，必须覆盖全部字段）
     */
    public void reset(WebSocketSession session, PictureEditMessageTypeEnum messageType, String rawMessage) {
        this.session = session;
        this.sessionId = session == null ? null : session.getId();
        this.pictureId = null;
        this.userId = null;
        this.rawMessage = rawMessage;
        this.messageType = messageType;
        this.payload = null;
        this.room = null;
        this.errorMessage = null;
    }

    public boolean isInvalid() {
        return errorMessage != null;
    }
}

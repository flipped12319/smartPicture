package com.flipped.picturebackend.manager.ws;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.flipped.picturebackend.model.dto.ws.PictureEditRequestMessage;
import com.flipped.picturebackend.model.enums.PictureEditMessageTypeEnum;
import com.flipped.picturebackend.model.vo.PictureEditParticipantVO;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 分发器：流水线的第一阶段
 * <p>
 * 职责只有两件：把客户端报文解析成结构化的消息（解析失败就标记为错误消息），
 * 以及按图片 id 把事件路由到对应的编辑房间。真正的业务处理交给消费者。
 * <p>
 * 放在这个阶段解析报文（而不是网络接收线程），是为了让接收线程只做入队这一件事。
 */
@Slf4j
@Component
public class EditMessageDispatcher implements EventHandler<EditEvent> {

    @Resource
    private EditRoomManager editRoomManager;

    @Override
    public void onEvent(EditEvent event, long sequence, boolean endOfBatch) {
        WebSocketSession session = event.getSession();
        if (session == null) {
            event.setErrorMessage("连接不存在");
            return;
        }
        // 客户端报文才需要判断连接是否还活着；
        // LEAVE 是连接关闭后由服务端产生的，此时连接已经不可用了
        if (event.getMessageType() == null && !session.isOpen()) {
            event.setErrorMessage("连接已关闭");
            return;
        }

        // 1. 服务端主动产生的消息（INFO / LEAVE）不需要解析报文
        if (event.getMessageType() == null) {
            PictureEditRequestMessage request = parseRequest(event.getRawMessage());
            if (request == null) {
                event.setErrorMessage("消息格式错误，无法解析");
                return;
            }
            PictureEditMessageTypeEnum type = PictureEditMessageTypeEnum.getEnumByValue(request.getType());
            if (type == null) {
                event.setErrorMessage("不支持的消息类型：" + request.getType());
                return;
            }
            // INFO / LEAVE 由服务端在连接建立与断开时产生，ERROR 是服务端下发的
            if (PictureEditMessageTypeEnum.INFO.equals(type)
                    || PictureEditMessageTypeEnum.LEAVE.equals(type)
                    || PictureEditMessageTypeEnum.ERROR.equals(type)) {
                event.setErrorMessage("该消息类型不支持客户端发送：" + type.getText());
                return;
            }
            event.setMessageType(type);
            event.setPayload(request.getPayload());
        }

        // 2. 取出握手阶段就解析好的图片 id 与用户信息
        Map<String, Object> attributes = session.getAttributes();
        Object pictureId = attributes.get(PictureEditHandshakeInterceptor.ATTR_PICTURE_ID);
        if (!(pictureId instanceof Long)) {
            event.setErrorMessage("连接缺少图片信息，请重新进入编辑");
            return;
        }
        event.setPictureId((Long) pictureId);
        Object participant = attributes.get(PictureEditHandshakeInterceptor.ATTR_PARTICIPANT);
        if (participant instanceof PictureEditParticipantVO) {
            event.setUserId(((PictureEditParticipantVO) participant).getUserId());
        }

        // 3. 按图片 id 路由到对应的编辑房间
        event.setRoom(editRoomManager.getOrCreateRoom(event.getPictureId()));
    }

    /**
     * 解析客户端报文
     *
     * @return 解析失败返回 null
     */
    private PictureEditRequestMessage parseRequest(String rawMessage) {
        if (StrUtil.isBlank(rawMessage)) {
            return null;
        }
        try {
            return JSONUtil.toBean(rawMessage, PictureEditRequestMessage.class);
        } catch (Exception e) {
            log.warn("解析协同编辑消息失败：{}", rawMessage, e);
            return null;
        }
    }
}

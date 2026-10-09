package com.flipped.picturebackend.model.dto.ws;

import com.flipped.picturebackend.model.vo.PictureEditParticipantVO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 服务端广播的协同编辑消息
 */
@Data
public class PictureEditResponseMessage implements Serializable {

    /**
     * 消息类型，见 PictureEditMessageTypeEnum
     */
    private Integer type;

    /**
     * 本次消息的可读描述，例如「用户 A 开始编辑」
     */
    private String message;

    /**
     * 触发这条消息的用户
     */
    private PictureEditParticipantVO user;

    /**
     * 当前正在编辑的用户 id；为空表示没有人在编辑
     */
    private Long editorUserId;

    /**
     * 当前正在编辑的用户名，便于前端直接提示
     */
    private String editorUserName;

    /**
     * 房间内的全部参与者，用于前端渲染「谁在编辑、谁在看」
     */
    private List<PictureEditParticipantVO> participants;

    /**
     * 编辑操作内容，仅 type = OPERATION 时透传
     */
    private String payload;

    /**
     * 本轮编辑的操作记录，仅在「新成员加入时私下补发」的同步消息里携带，
     * 前端按顺序回放这些操作即可把画布追平
     */
    private List<String> operationHistory;

    private static final long serialVersionUID = 1L;
}

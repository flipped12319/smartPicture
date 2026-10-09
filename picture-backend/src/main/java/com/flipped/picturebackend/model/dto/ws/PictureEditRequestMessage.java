package com.flipped.picturebackend.model.dto.ws;

import lombok.Data;

import java.io.Serializable;

/**
 * 前端发来的协同编辑消息
 * <p>
 * 图片 id 在建立 WebSocket 连接时通过查询参数传入（一个连接只服务于一张图片），
 * 因此这里不需要再带图片 id。
 */
@Data
public class PictureEditRequestMessage implements Serializable {

    /**
     * 消息类型，见 PictureEditMessageTypeEnum
     */
    private Integer type;

    /**
     * 编辑操作内容，仅在 type = OPERATION 时使用，结构由前端自行约定
     */
    private String payload;

    private static final long serialVersionUID = 1L;
}

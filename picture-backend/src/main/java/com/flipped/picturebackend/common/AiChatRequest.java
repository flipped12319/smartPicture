package com.flipped.picturebackend.common;

import lombok.Data;

import java.util.List;

@Data
public class AiChatRequest {
    /**
     * 会话ID，用于区分不同用户或对话，对应 LangGraph 的 thread_id
     */
    private String session_id;

    /**
     * 用户输入的文本，允许为空字符串（当只有图片时）
     */
    private String message;

    /**
     * 可选的 Base64 编码图片列表（不含 data:image/... 前缀）
     */
    private List<String> images;

    private String token;

    private String spaceId;
}
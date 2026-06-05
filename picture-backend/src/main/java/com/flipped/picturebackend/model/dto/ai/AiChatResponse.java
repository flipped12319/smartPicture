package com.flipped.picturebackend.model.dto.ai;

import lombok.Data;
import java.util.List;

@Data
public class AiChatResponse {
    private String reply;      // 智能体的回复文本
    private List<String> image_urls;  // 智能体返回的图片链接或 Base64（按需）
}
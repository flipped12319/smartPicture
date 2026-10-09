package com.flipped.picturebackend.controller;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.common.AiChatRequest;
import com.flipped.picturebackend.model.dto.ai.AiChatResponse;
import com.flipped.picturebackend.service.ChatAgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiController {
    @Autowired
    private ChatAgentService chatAgentService;


    @PostMapping("/chat")
    public BaseResponse<AiChatResponse> chat(@RequestBody AiChatRequest request) {
        // 调用 Python Agent 服务（通过 HTTP 或消息队列）
        AiChatResponse response = chatAgentService.sendToAgent(request);
        return ResultUtils.success(response);
    }
}

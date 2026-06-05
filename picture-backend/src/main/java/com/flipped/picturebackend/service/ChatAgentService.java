package com.flipped.picturebackend.service;

import com.flipped.picturebackend.common.AiChatRequest;
import com.flipped.picturebackend.model.dto.ai.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class ChatAgentService {

    @Value("${agent.api.url:http://localhost:8000/chat}")
    private String agentApiUrl;

    private final RestTemplate restTemplate;

    public ChatAgentService() {
        this.restTemplate = new RestTemplate();
    }

    public AiChatResponse sendToAgent(AiChatRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 可选：添加认证头，如 API Key
        // headers.set("X-API-Key", "your-secret-key");

        HttpEntity<AiChatRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<AiChatResponse> response = restTemplate.exchange(
                agentApiUrl,
                HttpMethod.POST,
                entity,
                AiChatResponse.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 Agent 服务失败", e);
            // 返回一个友好的错误响应
            AiChatResponse errorResp = new AiChatResponse();
            errorResp.setReply("抱歉，智能助手暂时无法响应，请稍后重试。");
            return errorResp;
        }
    }
}
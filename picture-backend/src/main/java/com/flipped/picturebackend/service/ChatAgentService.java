package com.flipped.picturebackend.service;

import com.flipped.picturebackend.common.AiChatRequest;
import com.flipped.picturebackend.model.dto.ai.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class ChatAgentService {

    @Value("${agent.api.url:http://localhost:8000/chat}")
    private String agentApiUrl;

    private final RestTemplate restTemplate;

    public ChatAgentService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时短：Agent 没启动时立刻失败，不要占着 Tomcat 线程干等
        factory.setConnectTimeout(2000);
        // 读超时要有上限：Agent 一次对话通常 10~30 秒，慢的工具链可能更久。
        // 不给超时（RestTemplate 默认无限等待）时，Agent 一旦卡住会永久占用请求线程 ——
        // 并发上来后 Tomcat 线程会被迅速耗尽，属于必须避免的情况。
        factory.setReadTimeout(90000);
        this.restTemplate = new RestTemplate(factory);
    }

    public AiChatResponse sendToAgent(AiChatRequest request) {
        // 归一化可空字段再转发。
        // AiChatRequest 的 token / spaceId 允许为 null，而 Python 侧这两个字段是必填字符串：
        // 传 null 过去会被 Pydantic 判 422，再被这里映射成笼统的「智能助手暂时无法响应」，
        // 前端完全看不出是参数问题。对方也已做兼容，这里主动归一算是双保险。
        if (request.getToken() == null) {
            request.setToken("");
        }
        if (request.getSpaceId() == null) {
            request.setSpaceId("");
        }

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
        } catch (HttpStatusCodeException e) {
            // 区分「过载」和「真故障」：Agent 限流时返回 429，这时提示用户重试，
            // 而不是笼统地说"暂时无法响应"（那样用户会以为功能坏了）
            int status = e.getRawStatusCode();
            log.error("调用 Agent 服务返回异常状态码: {}", status);
            AiChatResponse errorResp = new AiChatResponse();
            if (status == 429 || status == 503) {
                errorResp.setReply("当前使用人数较多，请稍后重试。");
            } else {
                errorResp.setReply("抱歉，智能助手暂时无法响应，请稍后重试。");
            }
            return errorResp;
        } catch (ResourceAccessException e) {
            // 连接失败或超时
            log.error("调用 Agent 服务失败（连接或超时）", e);
            AiChatResponse errorResp = new AiChatResponse();
            errorResp.setReply("智能助手响应超时，请稍后重试。");
            return errorResp;
        } catch (Exception e) {
            log.error("调用 Agent 服务失败", e);
            AiChatResponse errorResp = new AiChatResponse();
            errorResp.setReply("抱歉，智能助手暂时无法响应，请稍后重试。");
            return errorResp;
        }
    }
}

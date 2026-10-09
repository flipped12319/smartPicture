package com.flipped.picturebackend.config;

import com.flipped.picturebackend.manager.ws.PictureEditHandshakeInterceptor;
import com.flipped.picturebackend.manager.ws.PictureEditWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

/**
 * WebSocket 配置
 * <p>
 * 实际访问地址：ws://host:8123/api/ws/picture/edit?pictureId=xxx
 * （context-path 是 /api，对 WebSocket 同样生效）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private PictureEditWebSocketHandler pictureEditWebSocketHandler;

    @Resource
    private PictureEditHandshakeInterceptor pictureEditHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pictureEditWebSocketHandler, "/ws/picture/edit")
                // 前端开发服务器与后端不同源，这里放行跨域握手
                .setAllowedOriginPatterns("*")
                // 握手阶段完成登录与权限校验，没权限的连接直接拒绝
                .addInterceptors(pictureEditHandshakeInterceptor);
    }
}

package com.flipped.picturebackend.manager.ws;

import cn.hutool.core.util.StrUtil;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.PictureEditParticipantVO;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 协同编辑的握手拦截器：在真正建立 WebSocket 连接之前完成登录与权限校验。
 * <p>
 * 只有「能修改这张图片」的人才允许加入：
 * 公共图库的图片仅管理员可协同编辑，空间图片需要该空间的「编辑者」权限。
 * 校验不通过直接返回 false，连接会被拒绝，不会进入编辑房间。
 * <p>
 * 图片 id 与用户信息在这里解析一次后放进连接属性，后面的分发器、消费者直接复用，
 * 避免每次收到消息都重复查库。
 */
@Slf4j
@Component
public class PictureEditHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * 连接属性：被协同编辑的图片 id
     */
    public static final String ATTR_PICTURE_ID = "pictureEditPictureId";

    /**
     * 连接属性：握手阶段解析好的参与者信息
     */
    public static final String ATTR_PARTICIPANT = "pictureEditParticipant";

    @Resource
    private IPictureService pictureService;

    @Resource
    private UserService userService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            log.warn("协同编辑握手失败：当前请求不是 Servlet 请求");
            return false;
        }
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams();

        // 阶段 3：登录态以 JWT 为准。但浏览器的 WebSocket API **不支持自定义请求头**，
        // 没法像 axios 那样带 Authorization，所以这里通过查询参数 ?token= 接收。
        // 没带 token 时回退 Session（过渡期兜底）。
        User loginUser;
        try {
            String token = queryParams.getFirst("token");
            loginUser = StrUtil.isNotBlank(token)
                    ? userService.getLoginUserByToken(token)
                    : userService.getLoginUser(servletRequest);
        } catch (Exception e) {
            log.warn("协同编辑握手失败：用户未登录");
            return false;
        }
        String pictureIdValue = queryParams.getFirst("pictureId");
        try {
            ThrowUtils.throwIf(StrUtil.isBlank(pictureIdValue), ErrorCode.PARAMS_ERROR, "缺少 pictureId 参数");
            Long pictureId = Long.valueOf(pictureIdValue.trim());
            Picture picture = pictureService.getById(pictureId);
            ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 权限校验：没有修改权限的人不允许加入协同编辑
            pictureService.checkPictureModifyAuth(loginUser, picture);

            attributes.put(ATTR_PICTURE_ID, pictureId);
            attributes.put(ATTR_PARTICIPANT, buildParticipant(loginUser));
            return true;
        } catch (Exception e) {
            log.warn("协同编辑握手失败：用户 {}({}) 无权编辑图片 {}，原因：{}",
                    loginUser.getUserName(), loginUser.getId(), pictureIdValue, e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.warn("协同编辑握手异常", exception);
        }
    }

    private PictureEditParticipantVO buildParticipant(User loginUser) {
        PictureEditParticipantVO participant = new PictureEditParticipantVO();
        participant.setUserId(loginUser.getId());
        participant.setUserName(loginUser.getUserName());
        participant.setUserAvatar(loginUser.getUserAvatar());
        participant.setEditing(false);
        return participant;
    }
}

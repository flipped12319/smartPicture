package com.flipped.picturebackend.controller;


import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/")
public class MainController {

    @Resource
    private UserService userService;
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public BaseResponse<User> health(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        User user=userService.getLoginUserByToken(token);
        return ResultUtils.success(user);
    }
}

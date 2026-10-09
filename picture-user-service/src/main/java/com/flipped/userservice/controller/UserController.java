package com.flipped.userservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.userservice.common.BaseResponse;
import com.flipped.userservice.common.DeleteRequest;
import com.flipped.userservice.common.LoginResponse;
import com.flipped.userservice.common.ResultUtils;
import com.flipped.userservice.exception.BusinessException;
import com.flipped.userservice.exception.ErrorCode;
import com.flipped.userservice.exception.ThrowUtils;
import com.flipped.userservice.model.dto.UserLoginRequest;
import com.flipped.userservice.model.dto.UserRegisterRequest;
import com.flipped.userservice.model.dto.user.UserAddRequest;
import com.flipped.userservice.model.dto.user.UserQueryRequest;
import com.flipped.userservice.model.dto.user.UserUpdateRequest;
import com.flipped.userservice.model.entity.User;
import com.flipped.userservice.model.vo.LoginUserVO;
import com.flipped.userservice.model.vo.UserVO;
import com.flipped.userservice.service.UserService;
import com.flipped.userservice.util.JwtUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 用户接口（user 表的唯一属主）
 * <p>
 * 路径与单体原来的完全一致（/user/...），所以前端与网关契约不用改 ——
 * 网关只要把 /api/user/** 路由到本服务，调用方无感知。
 * <p>
 * 与单体的差异：单体靠 {@code @AuthCheck} 注解 + AOP 做管理员校验，
 * 这里直接内联判断，省掉一套注解与拦截器（本服务的权限点只有「管理员」一种）。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 管理员校验：非管理员直接抛无权限
     */
    private User requireAdmin(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(!userService.isAdmin(loginUser), ErrorCode.NO_AUTH_ERROR, "无权限");
        return loginUser;
    }

    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        long result = userService.userRegister(userRegisterRequest.getUserAccount(),
                userRegisterRequest.getUserPassword(), userRegisterRequest.getCheckPassword());
        return ResultUtils.success(result);
    }

    @PostMapping("/login")
    public BaseResponse<LoginResponse> userLogin(@RequestBody UserLoginRequest userLoginRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        LoginUserVO loginUserVO = userService.userLogin(userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword(), request);
        // JWT 由本服务统一签发；单体与网关用同一个 secret 校验
        String token = jwtUtil.generateToken(loginUserVO.getId());
        return ResultUtils.success(new LoginResponse(loginUserVO, token));
    }

    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        return ResultUtils.success(userService.getLoginUserVO(userService.getLoginUser(request)));
    }

    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        return ResultUtils.success(userService.userLogout(request));
    }

    @PostMapping("/add")
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        requireAdmin(request);
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        // 默认密码
        final String DEFAULT_PASSWORD = "12345678";
        user.setUserPassword(userService.getEncryptPassword(DEFAULT_PASSWORD));
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(user.getId());
    }

    @GetMapping("/get")
    public BaseResponse<User> getUserById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        requireAdmin(request);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 公开的脱敏用户信息（刻意不调 {@link #getUserById}：
     * 同类自调用不会走 Spring 代理，会让管理员校验静默失效）
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userService.getUserVO(user));
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest,
                                            HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        requireAdmin(request);
        return ResultUtils.success(userService.removeById(deleteRequest.getId()));
    }

    @PostMapping("/update")
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest,
                                            HttpServletRequest request) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        requireAdmin(request);
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    @PostMapping("/list/page/vo")
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        requireAdmin(request);
        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(new Page<>(current, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }
}

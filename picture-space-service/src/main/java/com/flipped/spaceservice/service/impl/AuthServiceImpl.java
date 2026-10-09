package com.flipped.spaceservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.flipped.spaceservice.common.BaseResponse;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.feign.UserClient;
import com.flipped.spaceservice.model.enums.UserRoleEnum;
import com.flipped.spaceservice.model.vo.UserVO;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用户能力实现：token 解析在本地完成，用户资料向 user-service 查询。
 * <p>
 * 为什么鉴权路径一定要再问一次 user-service：签名只证明「token 是我们签发的」，
 * 不证明用户还在（被删号的 token 在过期前仍然可用）。
 */
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private UserClient userClient;

    @Override
    public Long resolveLoginUserId(String authorizationHeader) {
        if (StrUtil.isBlank(authorizationHeader) || !authorizationHeader.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (StrUtil.isBlank(token) || !jwtUtil.validateToken(token)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "Token无效或已过期");
        }
        Long userId;
        try {
            userId = jwtUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "Token无效或已过期");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 确认用户仍存在。这里刻意不复用「失败即返回 null」的渲染路径：
        // 鉴权路径必须区分「token 无效」和「依赖服务挂了」，否则前者会被当成后者、
        // 或者反过来把故障说成未登录，两种都很难排查。
        UserClient.InternalUser user;
        try {
            user = fetchUser(userId);
        } catch (Exception e) {
            log.error("鉴权时查询用户失败，userId = {}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户服务暂不可用，请稍后重试");
        }
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return userId;
    }

    @Override
    public boolean isAdmin(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        try {
            UserClient.InternalUser user = fetchUser(userId);
            return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
        } catch (Exception e) {
            // 失败关闭：依赖不可用时不能把管理员权限放出去
            log.error("判断管理员身份失败，已按「非管理员」处理，userId = {}", userId, e);
            return false;
        }
    }

    @Override
    public UserVO toUserVO(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        try {
            UserClient.InternalUser user = fetchUser(userId);
            return user == null ? null : toUserVO(user);
        } catch (Exception e) {
            log.error("查询用户信息失败，已降级为「无昵称」，userId = {}", userId, e);
            return null;
        }
    }

    @Override
    public Map<Long, UserVO> toUserVOMap(Collection<Long> userIds) {
        Map<Long, UserVO> result = new HashMap<>();
        if (CollUtil.isEmpty(userIds)) {
            return result;
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long id : userIds) {
            if (id != null && id > 0) {
                distinct.add(id);
            }
        }
        // 成员列表一页最多 20 条，逐个查询的量级完全可接受；
        // 刻意不引入本地缓存：成员刚被邀请/改名后立刻要能看到，
        // 而该接口本身是低频的（不像图片列表那种热路径）。
        for (Long id : distinct) {
            UserVO vo = toUserVO(id);
            if (vo != null) {
                result.put(id, vo);
            }
        }
        return result;
    }

    @Override
    public UserClient.InternalUser getByAccount(String userAccount) {
        if (StrUtil.isBlank(userAccount)) {
            return null;
        }
        try {
            BaseResponse<UserClient.InternalUser> response = userClient.getByAccount(userAccount.trim());
            if (response == null || response.getCode() != 0) {
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.error("按账号查询用户失败，userAccount = {}", userAccount, e);
            return null;
        }
    }

    /**
     * 真正发起远程调用；失败时抛异常，由调用方决定是降级还是报错
     */
    private UserClient.InternalUser fetchUser(Long userId) {
        BaseResponse<UserClient.InternalUser> response = userClient.getById(userId);
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户服务返回空响应");
        }
        if (response.getCode() != 0) {
            return null;
        }
        return response.getData();
    }

    private UserVO toUserVO(UserClient.InternalUser user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }
}

package com.flipped.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.feign.UserClient;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.UserRoleEnum;
import com.flipped.picturebackend.model.vo.UserVO;
import com.flipped.picturebackend.service.UserService;
import com.flipped.picturebackend.util.JwtUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.flipped.picturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现（单体侧）
 * <p>
 * 阶段 3b：**不再持有 user 表**，改为通过 OpenFeign 调 picture-user-service。
 * <p>
 * 为什么加本地缓存：{@code getById} 在热路径上被大量调用
 * （列表渲染要拿作者信息、每次鉴权都要按 id 取用户），
 * 如果每次都跨服务调用，等于给最热的接口引入一次网络往返。
 * 这里用 Caffeine 缓存 userId → User，把绝大多数调用挡在本地。
 * <p>
 * 两条路径的失败策略刻意不同：
 * <ul>
 *     <li><b>鉴权路径</b>（getLoginUserByToken）：用户服务不可用时**明确报错**，
 *         不能静默当成「未登录」—— 那会让所有人都莫名其妙被登出，极难排查；</li>
 *     <li><b>渲染路径</b>（getById / listByIds）：**降级返回 null / 空集合**，
 *         让图片列表照常展示，只是暂时没有作者信息，比整页打不开好。</li>
 * </ul>
 */
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    /**
     * 用户本地缓存：写后 5 分钟过期。
     * 用户资料变更频率极低，5 分钟的短暂不一致可以接受；
     * 换来的是热路径上几乎不产生跨服务调用。
     */
    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(5);

    private final Cache<Long, User> userCache = Caffeine.newBuilder()
            .maximumSize(10_000L)
            .expireAfterWrite(USER_CACHE_TTL)
            .build();

    @Resource
    private UserClient userClient;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 真正发起远程调用；失败时抛异常，由调用方决定是降级还是报错
     */
    private User fetchUserFromRemote(Long id) {
        BaseResponse<User> response = userClient.getById(id);
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户服务返回空响应");
        }
        if (response.getCode() != 0) {
            log.warn("调 user-service 查用户失败，id = {}，code = {}，message = {}",
                    id, response.getCode(), response.getMessage());
            return null;
        }
        return response.getData();
    }

    @Override
    public User getById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        try {
            return userCache.get(id, this::fetchUserFromRemote);
        } catch (Exception e) {
            // 渲染路径：降级，不让用户服务的问题把图片列表整体拖垮
            log.error("查询用户失败，已降级为「无作者信息」，id = {}", id, e);
            return null;
        }
    }

    @Override
    public List<User> listByIds(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return new ArrayList<>();
        }
        List<Long> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            return new ArrayList<>();
        }
        // 先看缓存，只把没命中的 id 发到远端 —— 列表页里作者往往高度重复，这一步能省掉大部分调用
        Map<Long, User> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : distinctIds) {
            User cached = userCache.getIfPresent(id);
            if (cached != null) {
                result.put(id, cached);
            } else {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return new ArrayList<>(result.values());
        }
        try {
            BaseResponse<List<User>> response = userClient.listByIds(missing);
            if (response != null && response.getCode() == 0 && response.getData() != null) {
                for (User user : response.getData()) {
                    if (user != null && user.getId() != null) {
                        userCache.put(user.getId(), user);
                        result.put(user.getId(), user);
                    }
                }
            } else {
                log.warn("批量查询用户未成功，ids = {}，response = {}", missing, response);
            }
        } catch (Exception e) {
            // 渲染路径：降级为「缓存里有多少用多少」
            log.error("批量查询用户失败，已降级为使用本地缓存，ids = {}", missing, e);
        }
        return new ArrayList<>(result.values());
    }

    @Override
    public User getByAccount(String userAccount) {
        if (StrUtil.isBlank(userAccount)) {
            return null;
        }
        try {
            BaseResponse<User> response = userClient.getByAccount(userAccount);
            if (response == null || response.getCode() != 0) {
                return null;
            }
            User user = response.getData();
            if (user != null && user.getId() != null) {
                userCache.put(user.getId(), user);
            }
            return user;
        } catch (Exception e) {
            log.error("按账号查询用户失败，userAccount = {}", userAccount, e);
            return null;
        }
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 1. 优先 JWT（无状态，多实例可用）
        String authHeader = request.getHeader("Authorization");
        if (StrUtil.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            return getLoginUserByToken(authHeader.substring("Bearer ".length()).trim());
        }

        // 2. `/picture/proxy` 这类由 <img> 直接发起的请求带不了 Authorization 头
        //    （<img> 只能带 Cookie），只能把 token 放在查询参数里。**只认这一个参数名**，
        //    不做成通用兜底：查询参数会进访问日志/浏览器历史，越少用越好。
        String queryToken = request.getParameter("token");
        if (StrUtil.isNotBlank(queryToken)) {
            return getLoginUserByToken(queryToken.trim());
        }

        // 3. 过渡期兜底：Session（用 getSession(false)，不给无状态调用方凭空建会话）
        HttpSession session = request.getSession(false);
        Object userObj = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        User user = getById(currentUser.getId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public User getLoginUserByToken(String token) {
        if (StrUtil.isBlank(token)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 签名与过期校验在本地完成，不需要问 user-service
        if (!jwtUtil.validateToken(token)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "Token无效或已过期");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        User user;
        try {
            user = userCache.get(userId, this::fetchUserFromRemote);
        } catch (Exception e) {
            // 鉴权路径：明确报「服务不可用」，不伪装成「未登录」
            log.error("鉴权时查询用户失败，userId = {}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户服务暂不可用，请稍后重试");
        }
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return Collections.emptyList();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }
}

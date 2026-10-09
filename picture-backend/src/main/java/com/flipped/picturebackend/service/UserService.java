package com.flipped.picturebackend.service;

import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.List;

/**
 * 用户服务（单体侧）
 * <p>
 * 阶段 3b：单体**不再直接读写 user 表**，user 表已归 picture-user-service 所有。
 * 这里刻意保留原来的方法签名（只是不再继承 MyBatis-Plus 的 {@code IService}），
 * 因此几十处调用点（{@code getById} / {@code listByIds} / {@code getUserVO} …）一行都不用改，
 * 风险集中在本接口的实现里。
 * <p>
 * 登录/注册/用户增删改这些**写操作**已经整体搬到 user-service，
 * 单体只保留「读用户」与「校验登录态」两类能力。
 */
public interface UserService {

    /**
     * 获取当前登录用户：优先 JWT，没带 Authorization 时回退 Session
     */
    User getLoginUser(HttpServletRequest request);

    User getLoginUserByToken(String token);

    /**
     * 按 id 查用户（走 user-service + 本地缓存）。
     * 查询失败时**降级返回 null**，避免用户服务抖动导致图片列表整体不可用。
     */
    User getById(Long id);

    /**
     * 批量查用户（列表渲染时一次拿一批作者信息）
     */
    List<User> listByIds(Collection<Long> ids);

    /**
     * 按账号查用户（空间邀请时用）
     */
    User getByAccount(String userAccount);

    UserVO getUserVO(User user);

    List<UserVO> getUserVOList(List<User> userList);

    boolean isAdmin(User user);
}

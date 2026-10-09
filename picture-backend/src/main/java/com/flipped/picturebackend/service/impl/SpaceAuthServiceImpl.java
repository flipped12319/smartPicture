package com.flipped.picturebackend.service.impl;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.feign.SpaceClient;
import com.flipped.picturebackend.manager.SpaceCacheManager;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;
import com.flipped.picturebackend.service.SpaceAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 空间访问能力实现：本地 Caffeine 缓存 + OpenFeign 调 space-service。
 * <p>
 * 缓存策略与取舍见 {@link SpaceCacheManager} 的注释；这里只负责「缓存没命中时怎么问、
 * 问失败时怎么办」。**同一个远程调用，渲染路径与鉴权路径的失败处理刻意不同**：
 * 渲染路径降级返回 null，鉴权路径明确报错。
 */
@Service
@Slf4j
public class SpaceAuthServiceImpl implements SpaceAuthService {

    @Resource
    private SpaceClient spaceClient;

    @Resource
    private SpaceCacheManager spaceCacheManager;

    @Override
    public Space getSpaceForAuth(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            return null;
        }
        Space cached = spaceCacheManager.getSpace(spaceId);
        if (cached != null) {
            return cached;
        }
        // 依赖故障抛 50000，**绝不**返回 null 让调用方报「空间不存在」
        return fetchSpace(spaceId);
    }

    /**
     * 查空间（先看缓存）。
     * <p>
     * <b>本方法存在的唯一理由是「区分两种『查不到』」</b>，阶段 4 实测踩过坑：
     * <ol>
     *     <li>空间确实不存在 → 返回 null（space-service 对不存在的 id 返回
     *         {@code code=0, data=null}，那才是「真的没有」，调用方可以放心报 40400）；</li>
     *     <li>space-service 不可用 / 返回非 0 / 抛异常 → **抛 50000「空间服务暂不可用」**。</li>
     * </ol>
     * 踩过的坑就是第二类：以前它也被压成 null，于是后面每一处
     * {@code ThrowUtils.throwIf(space == null, "空间不存在")} 都会把一次「依赖抖动」
     * 报成「空间不存在」—— 用户按提示会以为空间被删了，排查方向直接跑偏。
     * <b>关键区别是 code 是否为 0，不能只看 data。</b>
     */
    private Space fetchSpace(Long spaceId) {
        BaseResponse<Space> response;
        try {
            response = spaceClient.getById(spaceId);
        } catch (Exception e) {
            log.error("查询空间失败（space-service 不可用），spaceId = {}", spaceId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        if (response == null) {
            log.error("调 space-service 查空间返回空响应，spaceId = {}", spaceId);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        if (response.getCode() != 0) {
            log.error("调 space-service 查空间失败，spaceId = {}，code = {}，message = {}",
                    spaceId, response.getCode(), response.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        Space space = response.getData();
        if (space != null) {
            spaceCacheManager.putSpace(space);
        }
        // 空间确实不存在时不缓存：否则「刚建好的空间」会被 60 秒的旧结果挡住
        return space;
    }

    @Override
    public Integer getRole(Long spaceId, Long userId) {
        if (spaceId == null || spaceId <= 0 || userId == null || userId <= 0) {
            return null;
        }
        if (spaceCacheManager.hasRole(spaceId, userId)) {
            return spaceCacheManager.getRole(spaceId, userId);
        }
        try {
            return fetchRole(spaceId, userId, false);
        } catch (Exception e) {
            log.error("查询空间角色失败，spaceId = {}，userId = {}", spaceId, userId, e);
            return null;
        }
    }

    @Override
    public void checkSpaceUserAuth(Space space, Long loginUserId, Integer requireRole) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        // 调用方（图片/相册链路）都是先 getById 再判空，走到这里 space 必然非空。
        // 仍然留一道兜底：真为 null 时按 40400 报——与拆分前的行为一致。
        // 注意这里**不再**为了「确认到底存不存在」再发一次远程调用：那会给热路径多加一跳，
        // 而且会把「依赖抖动」变成「空间不存在」。区分依赖故障的责任在 getById 的调用点
        // （见 getSpace/fetchSpace 的注释）。
        ThrowUtils.throwIf(space == null || space.getId() == null,
                ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        Long spaceId = space.getId();

        // 鉴权路径：依赖不可用时 fetchRole(..., true) 会抛 50000，绝不静默放行
        Integer role = spaceCacheManager.hasRole(spaceId, loginUserId)
                ? spaceCacheManager.getRole(spaceId, loginUserId)
                : fetchRole(spaceId, loginUserId, true);

        if (role == null) {
            // 走到这里只可能是「用户不是该空间成员」：空间对象是调用方给的，
            // 能给出 Space 就说明空间存在（这一点与拆分前的语义完全一致）。
            SpaceUserRoleEnum required = required(requireRole);
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "没有空间权限，需要「" + required.getText() + "」或更高权限");
        }

        SpaceUserRoleEnum roleEnum = SpaceUserRoleEnum.getEnumByValue(role);
        SpaceUserRoleEnum required = required(requireRole);
        if (!SpaceUserRoleEnum.hasPermission(roleEnum, required)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "没有空间权限，需要「" + required.getText() + "」或更高权限");
        }
    }

    @Override
    public void evictSpaceCache(Long spaceId) {
        spaceCacheManager.evictSpace(spaceId);
    }

    /**
     * 查角色（先看缓存）。
     *
     * @param strict true = 鉴权路径：依赖不可用时抛 50000；false = 渲染路径：返回 null
     */
    private Integer fetchRole(Long spaceId, Long userId, boolean strict) {
        BaseResponse<Integer> response;
        try {
            response = spaceClient.getRole(spaceId, userId);
        } catch (Exception e) {
            if (strict) {
                log.error("鉴权时查询空间角色失败，spaceId = {}，userId = {}", spaceId, userId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
            }
            throw e;
        }
        if (response == null || response.getCode() != 0) {
            if (strict) {
                log.error("鉴权时查询空间角色未成功，spaceId = {}，userId = {}，response = {}",
                        spaceId, userId, response);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
            }
            log.warn("调 space-service 查角色未成功，spaceId = {}，userId = {}，response = {}",
                    spaceId, userId, response);
            return null;
        }
        Integer role = response.getData();
        // 只缓存真实角色：「不是成员」这个结果如果也缓存，刚被邀请加入的成员会被挡住
        if (role != null) {
            spaceCacheManager.putRole(spaceId, userId, role);
        }
        return role;
    }

    /**
     * 明确确认空间是否存在。
     * <p>
     * 刻意**绕过本地缓存**：这个方法只在「角色查不到」时被调用，用途就是区分
     * 「空间不存在」和「用户不是成员」，用缓存反而可能把旧结果当成结论。
     */
    private boolean spaceExistsStrict(Long spaceId) {
        BaseResponse<Space> response;
        try {
            response = spaceClient.getById(spaceId);
        } catch (Exception e) {
            log.error("确认空间是否存在时调用失败，spaceId = {}", spaceId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        if (response == null || response.getCode() != 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        Space space = response.getData();
        if (space != null) {
            spaceCacheManager.putSpace(space);
        }
        return space != null;
    }

    private SpaceUserRoleEnum required(Integer requireRole) {
        SpaceUserRoleEnum required = requireRole == null
                ? SpaceUserRoleEnum.VIEWER : SpaceUserRoleEnum.getEnumByValue(requireRole);
        return required == null ? SpaceUserRoleEnum.VIEWER : required;
    }
}

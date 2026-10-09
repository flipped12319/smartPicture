package com.flipped.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.feign.SpaceClient;
import com.flipped.picturebackend.manager.SpaceCacheManager;
import com.flipped.picturebackend.model.entity.LocalMessage;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.SpaceVO;
import com.flipped.picturebackend.mq.MessageRelay;
import com.flipped.picturebackend.mq.MqConfig;
import com.flipped.picturebackend.mq.MqMessage;
import com.flipped.picturebackend.mq.QuotaChangedPayload;
import com.flipped.picturebackend.service.SpaceAuthService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.SpaceUserService;
import com.flipped.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 空间服务实现（单体侧）
 * <p>
 * 阶段 4b：**不再持有 space 表**。读走 {@link SpaceAuthService}（本地缓存 + Feign），
 * 写走 {@link SpaceClient}。方法签名与拆分前一致，调用点没有改动。
 */
@Service
@Slf4j
public class SpaceServiceImpl implements SpaceService {

    @Resource
    private SpaceClient spaceClient;

    @Resource
    private SpaceAuthService spaceAuthService;

    @Resource
    private SpaceUserService spaceUserService;

    @Resource
    private SpaceCacheManager spaceCacheManager;

    @Resource
    private UserService userService;

    /**
     * 消息可靠投递器（阶段 5d-b）。
     * <p>
     * 用 ObjectProvider：MQ 关闭时这个 bean 不存在，直接注入会让应用起不来 ——
     * 那就违背了「不开 MQ 也能跑」的底线。拿不到就回落到同步 Feign。
     */
    @Resource
    private ObjectProvider<MessageRelay> messageRelayProvider;

    /**
     * 消息来源标识（排查时用来区分是谁发的）
     */
    private static final String SOURCE = "picture-backend";

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, Long loginUserId) {
        Page<SpaceVO> voPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        List<Space> spaceList = spacePage.getRecords();
        if (CollUtil.isEmpty(spaceList)) {
            return voPage;
        }
        List<SpaceVO> voList = spaceList.stream().map(SpaceVO::objToVo).collect(Collectors.toList());
        // 批量查作者信息：单体的 UserService 本身就是「Feign + 本地缓存」，
        // 所以这一步不会打库，也不会给列表接口新增跨服务往返
        Set<Long> userIdSet = spaceList.stream()
                .map(Space::getUserId)
                .filter(Objects::nonNull)
                .filter(userId -> userId > 0)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (CollUtil.isNotEmpty(userIdSet)) {
            userMap = userService.listByIds(userIdSet).stream()
                    .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        }
        for (int i = 0; i < voList.size(); i++) {
            SpaceVO vo = voList.get(i);
            vo.setUser(userService.getUserVO(userMap.get(spaceList.get(i).getUserId())));
        }
        // 批量解析「当前用户在这批空间里的角色」，避免逐个空间跨服务调用
        if (loginUserId != null) {
            List<Long> spaceIds = voList.stream().map(SpaceVO::getId)
                    .filter(Objects::nonNull).collect(Collectors.toList());
            spaceUserService.getSpaceRoleMap(spaceIds, loginUserId).forEach((spaceId, role) -> {
                voList.stream()
                        .filter(vo -> spaceId.equals(vo.getId()))
                        .findFirst()
                        .ifPresent(vo -> vo.setCurrentUserRole(role.getValue()));
            });
        }
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public void checkSpaceAuth(Long loginUserId, Space space) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 仅本人或平台管理员
        if (!space.getUserId().equals(loginUserId) && !isAdmin(loginUserId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    @Override
    public Long getSpaceIdByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        try {
            BaseResponse<Long> response = spaceClient.getSpaceIdByUserId(userId);
            if (response == null) {
                return null;
            }
            if (response.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()) {
                // 该用户还没有私人空间，属于正常情况（相册模块与智能助手都依赖这个语义）
                return null;
            }
            if (response.getCode() != 0) {
                log.warn("调 space-service 查私人空间 id 失败，userId = {}，code = {}，message = {}",
                        userId, response.getCode(), response.getMessage());
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.error("查询用户私人空间 id 失败，userId = {}", userId, e);
            return null;
        }
    }

    @Override
    public Space getById(Long id) {
        // 鉴权/写路径语义：空间确实不存在时返回 null（调用方据此报 40400），
        // 但 space-service 不可用时**抛 50000**，不会被误报成「空间不存在」。
        // 原因见 SpaceAuthService#getSpaceForAuth 的注释（阶段 4 实测踩过）。
        return spaceAuthService.getSpaceForAuth(id);
    }

    @Override
    public List<Space> listByIds(Collection<Long> ids) {
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
        Map<Long, Space> result = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        // 先看缓存，只把没命中的 id 发到远端（列表里空间往往重复，这一步能省掉大部分调用）
        for (Long id : distinctIds) {
            Space cached = spaceCacheManager.getSpace(id);
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
            BaseResponse<List<Space>> response = spaceClient.listByIds(missing);
            if (response != null && response.getCode() == 0 && response.getData() != null) {
                for (Space space : response.getData()) {
                    if (space != null && space.getId() != null) {
                        spaceCacheManager.putSpace(space);
                        result.put(space.getId(), space);
                    }
                }
            } else {
                log.warn("批量查询空间未成功，ids = {}，response = {}", missing, response);
            }
        } catch (Exception e) {
            // 渲染路径：能拿到多少用多少
            log.error("批量查询空间失败，已降级为使用本地缓存，ids = {}", missing, e);
        }
        return new ArrayList<>(result.values());
    }

    // ==================== 内部工具 ====================

    /**
     * 直接调用 space-service 改额度（**同步**）。仅用于补偿/归还场景。
     * <p>
     * 为什么补偿必须同步：它是「刚占住的额度立刻还回去」，用户马上还要再试一次上传 ——
     * 异步还额度会让用户看到「额度不足」却查不出原因。
     * 删除路径（还额度）则走 {@link #updateSpaceQuota} 的事件化版本。
     */
    @Override
    public void updateSpaceQuotaNow(Long spaceId, long sizeDelta, long countDelta, String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        SpaceClient.QuotaChangePayload payload = new SpaceClient.QuotaChangePayload();
        payload.setSpaceId(spaceId);
        payload.setSizeDelta(sizeDelta);
        payload.setCountDelta(countDelta);
        payload.setReason(reason);
        BaseResponse<Space> response;
        try {
            response = spaceClient.changeQuota(payload);
        } catch (Exception e) {
            log.error("调 space-service 调整配额失败，spaceId = {}，sizeDelta = {}，countDelta = {}",
                    spaceId, sizeDelta, countDelta, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        throwIfFailed(response);
        // 本地缓存里的额度已经过期（写入方是 space-service，不会通知本实例），
        // 主动清掉：下次读会拿到包含本次变更的最新值
        spaceCacheManager.evictSpace(spaceId);
    }

    /**
     * 额度变更（阶段 5d-b 起是**事件化**的：写本地消息表 + 发 {@code quota.changed}）
     *
     * <p><b>为什么这条路可以异步，而上传占额度不行</b>：
     * 这个方法的调用场景是**还额度**（删除图片、补偿），晚几百毫秒没有正确性影响；
     * 而「能不能占」必须同步 —— 超限要让用户立刻被拒绝，否则就会超卖。
     * 一条经验：**「能不能做」要同步，「记一笔账」可以异步。**
     *
     * <p>改异步带来的好处很直接：删除图片**不再依赖 space-service 可用**，
     * 之前它抖一下，用户连删图都删不了。代价是配额短暂偏小，最终一致。
     *
     * <p>MQ 关闭时回落到同步调用，行为与阶段 4 完全一致（这条底线贯穿整个阶段 5）。
     */
    @Override
    public void updateSpaceQuota(Long spaceId, long sizeDelta, long countDelta, String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        MessageRelay relay = messageRelayProvider.getIfAvailable();
        if (relay == null) {
            // MQ 没开：退回同步调用（发消息的通道不存在，只能直接改）
            log.debug("MQ 未启用，额度变更走同步 Feign：spaceId = {}", spaceId);
            updateSpaceQuotaNow(spaceId, sizeDelta, countDelta, reason);
            return;
        }
        QuotaChangedPayload payload = new QuotaChangedPayload(spaceId, sizeDelta, countDelta, reason);
        MqMessage<QuotaChangedPayload> message =
                MqMessage.of(MqConfig.QUOTA_ROUTING_KEY, SOURCE, payload);
        // 与业务数据同一个本地事务落库（调用方通常已在事务里）；
        // 提交后由定时任务补投，MQ 挂了也不会丢 —— 这就是 5c 那套地基的复用。
        LocalMessage record = relay.saveInTransaction(MqConfig.EXCHANGE, MqConfig.QUOTA_ROUTING_KEY, message);
        if (!relay.publishNow(record)) {
            log.warn("额度变更消息立即投递未成功，已留在本地消息表等待补投：spaceId = {}，sizeDelta = {}，" +
                    "countDelta = {}，messageId = {}", spaceId, sizeDelta, countDelta, record.getMessageId());
        }
        // 本地缓存可能还留着旧额度，清掉以免用户立刻看到过期数字
        spaceCacheManager.evictSpace(spaceId);
    }

    @Override
    public Space reserveSpaceQuota(Long spaceId, long sizeDelta, long countDelta, String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        SpaceClient.QuotaChangePayload payload = new SpaceClient.QuotaChangePayload();
        payload.setSpaceId(spaceId);
        payload.setSizeDelta(sizeDelta);
        payload.setCountDelta(countDelta);
        payload.setReason(reason);

        BaseResponse<SpaceClient.QuotaReserveResult> response;
        try {
            response = spaceClient.reserveQuota(payload);
        } catch (Exception e) {
            // 连不上是**故障**：抛 50000，调用方事务回滚（绝不能"先写图片再算额度"）
            log.error("调 space-service 预占额度失败，spaceId = {}，sizeDelta = {}，countDelta = {}",
                    spaceId, sizeDelta, countDelta, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务暂不可用，请稍后重试");
        }
        throwIfFailed(response);
        SpaceClient.QuotaReserveResult result = response.getData();
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务返回异常");
        }
        if (result.getLimited() != null) {
            // 额度不足是**业务判定**：原样抛出，让用户看到「空间条数不足 / 空间大小不足」。
            // 这里刻意不复用 50000 —— 否则「额度不够」会被显示成「系统错误」。
            throw new BusinessException(ErrorCode.OPERATION_ERROR, result.getLimited());
        }
        // 预占成功后额度已变，清掉本地缓存（写入方是 space-service，不会通知本实例）
        spaceCacheManager.evictSpace(spaceId);
        return result.getSpace();
    }

    private boolean isAdmin(Long userId) {
        return userService.isAdmin(userService.getById(userId));
    }

    /**
     * 把 space-service 的业务错误码原样转成异常：
     * 不能把它压成 50000，否则「空间不存在」这类提示会消失，排查时只剩「系统错误」。
     */
    private void throwIfFailed(BaseResponse<?> response) {
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "空间服务无响应");
        }
        if (response.getCode() != 0) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
    }
}

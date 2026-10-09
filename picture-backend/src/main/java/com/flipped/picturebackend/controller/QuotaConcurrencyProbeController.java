package com.flipped.picturebackend.controller;

import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.service.SpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发额度探针（阶段 5d-b，默认关闭）
 *
 * <p><b>为什么需要它</b>：5d-b 的核心目标是消除 TOCTOU（先查后扣的竞态）。
 * 这种问题**单线程测试永远测不出来** —— 必须让多个线程同时抢同一份额度才可能复现。
 * 所以专门做一个探针：N 个线程同时调 space-service 的原子预占，然后核对
 * 「成功数 × 每片额度」是否 ≤ 上限。
 *
 * <p>只挂在 {@code picture.mq.debug-enabled=true} 下：它能任意改额度，不能进生产。
 */
@RestController
@RequestMapping("/mq/debug")
@ConditionalOnProperty(name = "picture.mq.debug-enabled", havingValue = "true")
@Slf4j
public class QuotaConcurrencyProbeController {

    @Resource
    private SpaceService spaceService;

    /**
     * 让 N 个线程同时抢额度，返回成功/失败统计与前后快照。
     *
     * @param spaceId   目标空间
     * @param threads   并发线程数（每个线程抢一次）
     * @param sizeEach  每次抢的容量（字节）
     * @param countEach 每次抢的条数
     */
    @PostMapping("/probe-concurrent-reserve")
    public BaseResponse<Map<String, Object>> probeConcurrentReserve(
            @RequestParam Long spaceId,
            @RequestParam(defaultValue = "10") Integer threads,
            @RequestParam(defaultValue = "1024") Long sizeEach,
            @RequestParam(defaultValue = "1") Long countEach) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR, "spaceId 不合法");
        ThrowUtils.throwIf(threads == null || threads <= 0 || threads > 200, ErrorCode.PARAMS_ERROR, "threads 需在 1~200");

        Space before = spaceService.getById(spaceId);
        ThrowUtils.throwIf(before == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    // 所有线程在这里对齐，尽量同时发出请求 —— 竞态窗口才可能被打开
                    start.await();
                    spaceService.reserveSpaceQuota(spaceId, sizeEach, countEach, "probe:concurrent");
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    // 「额度不足」是 50001（OPERATION_ERROR，业务判定），
                    // 「空间服务不可用」是 50000（SYSTEM_ERROR，故障）—— 必须分开统计，
                    // 否则「全部失败」时看不出到底是抢不到额度还是服务挂了
                    if (e.getCode() == ErrorCode.OPERATION_ERROR.getCode()) {
                        limited.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                        log.warn("并发预占出现非「额度不足」的失败：code = {}，msg = {}", e.getCode(), e.getMessage());
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("并发预占异常", e);
                } finally {
                    done.countDown();
                }
            }, "quota-probe-" + i).start();
        }
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Space after = spaceService.getById(spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads", threads);
        result.put("sizeEach", sizeEach);
        result.put("countEach", countEach);
        result.put("success", ok.get());
        result.put("limited", limited.get());
        result.put("failed", failed.get());
        result.put("totalSizeBefore", before.getTotalSize());
        result.put("totalSizeAfter", after == null ? null : after.getTotalSize());
        result.put("totalCountBefore", before.getTotalCount());
        result.put("totalCountAfter", after == null ? null : after.getTotalCount());
        result.put("maxSize", before.getMaxSize());
        result.put("maxCount", before.getMaxCount());
        result.put("sizeWithinLimit", after != null && after.getTotalSize() != null
                && before.getMaxSize() != null && after.getTotalSize() <= before.getMaxSize());
        result.put("countWithinLimit", after != null && after.getTotalCount() != null
                && before.getMaxCount() != null && after.getTotalCount() <= before.getMaxCount());
        log.warn("并发额度探针结束：{}", result);
        return ResultUtils.success(result);
    }
}

package com.flipped.picturebackend.manager.ws;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 协同编辑的 Disruptor 队列
 * <p>
 * 结构：<b>生产者（网络线程入队） → 分发器（解析 + 路由） → 消费者（状态流转 + 广播）</b>。
 * 两个阶段挂在同一个环形队列上，用 sequence barrier 串成流水线，
 * 因此同一条消息一定会先过分发器、再过消费者，顺序不会乱。
 * <p>
 * 引入 Disruptor 的目的：WebSocket 的接收与响应原本在同一个线程上，
 * 并发一高，接收线程就会被业务处理（校验、广播）占住，导致响应变慢。
 * 把处理搬到独立线程后，接收线程只负责入队，能立刻返回。
 */
@Slf4j
@Component
public class EditDisruptorManager {

    /**
     * 环形队列长度，必须是 2 的幂
     */
    private static final int BUFFER_SIZE = 2048;

    @Resource
    private EditMessageDispatcher editMessageDispatcher;

    @Resource
    private EditMessageConsumer editMessageConsumer;

    /**
     * 环形队列，生产者通过它入队
     */
    @Getter
    private RingBuffer<EditEvent> ringBuffer;

    private Disruptor<EditEvent> disruptor;

    @PostConstruct
    public void start() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "picture-edit-disruptor-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        disruptor = new Disruptor<>(EditEvent::new, BUFFER_SIZE, threadFactory,
                ProducerType.MULTI, new BlockingWaitStrategy());
        // 生产者 → 分发器 → 消费者
        disruptor.handleEventsWith(editMessageDispatcher).then(editMessageConsumer);
        // 单条消息处理失败不应该拖垮整条流水线，记日志后继续处理后面的消息
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<EditEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, EditEvent event) {
                log.error("处理协同编辑消息异常，pictureId = {}，sequence = {}",
                        event == null ? null : event.getPictureId(), sequence, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("协同编辑消息队列启动失败", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("协同编辑消息队列关闭失败", ex);
            }
        });
        ringBuffer = disruptor.start();
        log.info("协同编辑消息队列已启动，环形队列长度 = {}", BUFFER_SIZE);
    }

    @PreDestroy
    public void stop() {
        if (disruptor == null) {
            return;
        }
        disruptor.shutdown();
        log.info("协同编辑消息队列已关闭");
    }
}

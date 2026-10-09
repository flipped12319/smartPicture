package com.flipped.picturebackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * <p>
 * 项目里原本已有 {@code @Async} 注解（如 clearPictureFile），但一直没有开启
 * {@code @EnableAsync}，所以那些注解实际上从未生效。这里统一开启并提供一个
 * 图片索引专用的线程池。
 * <p>
 * 注意：{@code @Async} 只能通过 Spring 代理生效，
 * 同一个类内部用 {@code this.xxx()} 自调用会绕过代理、仍然是同步执行。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 图片索引专用线程池
     * <p>
     * 索引要调用 Qwen 生成标签并计算 embedding，单张可能耗时数十秒，
     * 因此并发度不适宜太高，避免把下游的模型服务打满。
     */
    @Bean("pictureIndexExecutor")
    public Executor pictureIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("picture-index-");
        // 队列满时由调用线程执行，保证任务不丢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待正在执行的索引任务结束
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

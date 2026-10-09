package com.flipped.picturebackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
//mybatis-plus扫描mapper包
@MapperScan("com.flipped.picturebackend.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
// 阶段 3b：扫描 Feign 客户端（调 user-service 读用户信息）
@EnableFeignClients(basePackages = "com.flipped.picturebackend.feign")
// 阶段 5c：开启定时任务（本地消息补投、索引对账）。
// 此前项目没有任何 @Scheduled，这是第一处；定时任务本身都挂在 picture.mq.enabled 条件下，
// 所以这个注解在 MQ 关闭时也不会启动任何任务。
@EnableScheduling
public class PictureBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PictureBackendApplication.class, args);
    }

}

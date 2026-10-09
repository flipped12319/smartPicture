package com.flipped.spaceservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 空间与团队成员服务（阶段 4）
 * <p>
 * space 与 space_user 两张表的唯一属主：空间 CRUD、成员邀请/接受/移除、角色校验、配额读写。
 * 单体（picture-backend）不再读写这两张表，改由 OpenFeign 调本服务。
 * <p>
 * {@code @EnableFeignClients} 只扫描本服务自己的 feign 包：本服务依赖 user-service
 * （成员列表要展示成员与邀请人的昵称/头像）。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.flipped.spaceservice.feign")
public class SpaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaceServiceApplication.class, args);
    }
}

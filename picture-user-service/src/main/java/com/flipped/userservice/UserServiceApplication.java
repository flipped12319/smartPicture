package com.flipped.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户与认证服务（阶段 3b）
 * <p>
 * user 表的唯一属主。单体不再读写 user 表，改由 OpenFeign 调本服务。
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}

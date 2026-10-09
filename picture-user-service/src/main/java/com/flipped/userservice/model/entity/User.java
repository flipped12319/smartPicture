package com.flipped.userservice.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;

/**
 * 用户表实体（user 表的唯一属主是本服务）
 * <p>
 * 与单体里那份保持字段一致：单体虽然不再读写该表，但仍需要这个类作为
 * OpenFeign 的传输对象（JSON 字段名必须能对上）。
 */
@Data
@TableName(value = "user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "userAccount")
    private String userAccount;

    /**
     * 密码：@JsonIgnore 保证它不会随任何响应下发
     */
    @JsonIgnore
    @TableField(value = "userPassword")
    private String userPassword;

    @TableField(value = "userName")
    private String userName;

    @TableField(value = "userAvatar")
    private String userAvatar;

    @TableField(value = "userProfile")
    private String userProfile;

    @TableField(value = "userRole")
    private String userRole;

    private Date editTime;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    @TableField(value = "isDelete")
    private Integer isDelete;
}

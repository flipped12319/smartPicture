package com.flipped.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

/**
 * 用户表
 * @TableName user
 */
@Data
@TableName(value = "user")
public class User {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 账号
     */
    @TableField(value = "userAccount")
    private String userAccount;

    /**
     * 密码
     */
    @TableField(value = "userPassword")
    private String userPassword;

    /**
     * 用户昵称
     */
    @TableField(value = "userName")
    private String userName;

    /**
     * 用户头像
     */
    @TableField(value = "userAvatar")
    private String userAvatar;

    /**
     * 用户简介
     */
    @TableField(value = "userProfile")
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    @TableField(value = "userRole")
    private String userRole;

    /**
     * 编辑时间
     */
//    @TableField(value = "editTime", fill = FieldFill.INSERT_UPDATE)
    private Date editTime;

    /**
     * 创建时间
     */
//    @TableField(value = "createTime", fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
//    @TableField(value = "updateTime", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    @TableField(value = "isDelete")
    private Integer isDelete;
}
package com.flipped.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * <p>
     * 加 {@code @JsonIgnore}：有几个接口直接返回原始 User 实体
     * （例如 MainController#health、UserController#getUserById），
     * 不加的话密码哈希会随响应下发。只影响 JSON 序列化，不影响入库。
     */
    @JsonIgnore
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
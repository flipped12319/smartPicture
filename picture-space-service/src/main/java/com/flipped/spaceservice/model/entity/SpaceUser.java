package com.flipped.spaceservice.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 空间成员
 * <p>
 * 一条记录同时表达「邀请中」「已加入」「已拒绝」三种状态。
 * 该表为关联表，使用物理删除（无 isDelete 字段），
 * 以便依赖 (spaceId, userId) 唯一索引防止重复成员并支持移除后重新邀请。
 */
@TableName(value = "space_user")
@Data
public class SpaceUser implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 空间角色：0-只读 1-可上传 2-可编辑 3-管理员
     */
    private Integer spaceRole;

    /**
     * 状态：0-邀请中 1-已加入 2-已拒绝
     */
    private Integer status;

    /**
     * 邀请人 id
     */
    private Long inviterId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}

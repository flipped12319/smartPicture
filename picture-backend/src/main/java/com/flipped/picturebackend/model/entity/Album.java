package com.flipped.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 相册
 * <p>
 * 相册归属于用户的私人空间，内部保存的是对图片的「引用」，
 * 不会复制图片文件，也不占用空间的容量与数量额度。
 * <p>
 * 相册内的图片数量与封面都由 album_picture 关联表实时统计得出，
 * 不在此处冗余存储，避免图片增删后数据不一致。
 */
@TableName(value = "album")
@Data
public class Album implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 相册名称
     */
    private String name;

    /**
     * 相册说明
     */
    private String introduction;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 所属私人空间 id
     */
    private Long spaceId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}

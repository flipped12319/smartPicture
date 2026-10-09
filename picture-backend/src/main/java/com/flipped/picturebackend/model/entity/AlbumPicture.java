package com.flipped.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 相册图片关联
 * <p>
 * 纯关联表，采用物理删除：移出相册后记录直接删除，
 * 这样可以依赖 (albumId, pictureId) 唯一索引防止重复关联。
 */
@TableName(value = "album_picture")
@Data
public class AlbumPicture implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 相册 id
     */
    private Long albumId;

    /**
     * 图片 id
     */
    private Long pictureId;

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

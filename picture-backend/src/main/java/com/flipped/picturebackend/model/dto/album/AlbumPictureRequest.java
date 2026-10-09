package com.flipped.picturebackend.model.dto.album;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 相册内图片操作请求（批量加入 / 批量移出相册）
 */
@Data
public class AlbumPictureRequest implements Serializable {

    /**
     * 相册 id
     */
    private Long albumId;

    /**
     * 图片 id 列表
     */
    private List<Long> pictureIds;

    private static final long serialVersionUID = 1L;
}

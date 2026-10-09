package com.flipped.picturebackend.model.dto.album;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建相册请求
 */
@Data
public class AlbumAddRequest implements Serializable {

    /**
     * 相册名称
     */
    private String name;

    /**
     * 相册说明
     */
    private String introduction;

    /**
     * 初始加入相册的图片 id 列表（可为空）
     * 图片可以来自公共图库，也可以来自本人的私人空间
     */
    private List<Long> pictureIds;

    private static final long serialVersionUID = 1L;
}

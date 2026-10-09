package com.flipped.picturebackend.model.dto.album;

import lombok.Data;

import java.io.Serializable;

/**
 * 编辑相册请求（修改名称与说明）
 */
@Data
public class AlbumEditRequest implements Serializable {

    /**
     * 相册 id
     */
    private Long id;

    /**
     * 相册名称
     */
    private String name;

    /**
     * 相册说明
     */
    private String introduction;

    private static final long serialVersionUID = 1L;
}

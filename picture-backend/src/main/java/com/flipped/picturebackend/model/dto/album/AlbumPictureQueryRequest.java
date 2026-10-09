package com.flipped.picturebackend.model.dto.album;

import com.flipped.picturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 相册内图片分页查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AlbumPictureQueryRequest extends PageRequest implements Serializable {

    /**
     * 相册 id
     */
    private Long albumId;

    private static final long serialVersionUID = 1L;
}

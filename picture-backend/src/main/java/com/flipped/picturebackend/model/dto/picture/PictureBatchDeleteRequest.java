package com.flipped.picturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量删除图片请求
 */
@Data
public class PictureBatchDeleteRequest implements Serializable {

    /**
     * 要删除的图片 id 列表
     */
    private List<Long> ids;

    private static final long serialVersionUID = 1L;
}

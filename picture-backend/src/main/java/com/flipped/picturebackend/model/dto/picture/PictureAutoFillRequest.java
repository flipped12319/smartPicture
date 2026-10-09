package com.flipped.picturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

/**
 * 智能补充请求：让模型补齐图片中为空的名称、分类、简介、标签
 */
@Data
public class PictureAutoFillRequest implements Serializable {

    /**
     * 需要补充信息的图片 id
     */
    private Long pictureId;

    private static final long serialVersionUID = 1L;
}

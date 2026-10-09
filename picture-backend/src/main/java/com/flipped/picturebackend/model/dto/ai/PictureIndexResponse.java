package com.flipped.picturebackend.model.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 图片索引响应：Python 索引服务返回模型生成的内容
 * <p>
 * 这些字段只会在「图片原本为空」时被回填到图片表，不会覆盖用户填写的内容。
 */
@Data
public class PictureIndexResponse implements Serializable {

    /**
     * 是否已成功写入向量库
     */
    private Boolean indexed;

    /**
     * 模型生成的图片名称
     */
    private String name;

    /**
     * 模型生成的分类
     */
    private String category;

    /**
     * 模型生成的简介
     */
    private String introduction;

    /**
     * 模型生成的标签
     */
    private List<String> tags;

    /**
     * 附加信息（如生成失败时的降级说明）
     */
    private String message;

    private static final long serialVersionUID = 1L;
}

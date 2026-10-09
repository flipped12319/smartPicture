package com.flipped.picturebackend.model.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 图片索引请求：发给 Python 索引服务，由它生成标签并写入向量库
 */
@Data
public class PictureIndexRequest implements Serializable {

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 图片名称
     */
    private String name;

    /**
     * 简介
     */
    private String introduction;

    /**
     * 分类
     */
    private String category;

    /**
     * 用户已填写的标签（会一并参与 embedding，但不会被覆盖）
     */
    private List<String> tags;

    /**
     * 图片访问地址，Python 侧据此下载图片
     */
    private String url;

    /**
     * 空间 id，公共图库为 null
     */
    private Long spaceId;

    /**
     * 上传用户 id
     */
    private Long userId;

    /**
     * 审核状态：0-待审核 1-通过 2-拒绝（用于向量库的权限过滤）
     */
    private Integer reviewStatus;

    private static final long serialVersionUID = 1L;
}

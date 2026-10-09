package com.flipped.picturebackend.model.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片语义检索请求：发给 Python 索引服务
 * <p>
 * 只做「按相似度召回 id」，不下发图片正文 —— 正文与权限以 MySQL 为准，由调用方回表完成。
 */
@Data
public class PictureSearchRequest implements Serializable {

    /**
     * 查询文本
     */
    private String query;

    /**
     * 召回数量上限
     */
    private Integer topK;

    /**
     * 空间过滤：公共图库传空串（与索引写入时的元数据保持一致），不传表示不限空间
     */
    private String spaceId;

    /**
     * 最小相似度（0~1），不传表示不过滤
     */
    private Double minSimilarity;

    private static final long serialVersionUID = 1L;
}

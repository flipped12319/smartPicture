package com.flipped.picturebackend.model.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 图片语义检索响应：只包含图片 id 与相似度
 */
@Data
public class PictureSearchResponse implements Serializable {

    /**
     * 召回结果，已按相似度从高到低排序
     */
    private List<PictureSearchHit> hits;

    /**
     * 召回数量
     */
    private Integer total;

    /**
     * 附加说明（如查询文本为空时的提示）
     */
    private String message;

    @Data
    public static class PictureSearchHit implements Serializable {

        /**
         * 图片 id（字符串，避免精度问题）
         */
        private String pictureId;

        /**
         * 相似度，0 ~ 1，越大越相似
         */
        private Double similarity;

        /**
         * Chroma 返回的原始距离，越小越相似（仅用于调试与阈值标定）
         */
        private Double distance;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}

package com.flipped.picturebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片向量索引状态枚举
 */
@Getter
public enum PictureIndexStatusEnum {

    /**
     * 尚未索引：例如公共图库中还未通过审核的图片
     */
    PENDING("未索引", 0),

    /**
     * 已成功写入向量库
     */
    SUCCESS("已索引", 1),

    /**
     * 索引失败，需要人工或定时任务重试
     */
    FAILED("索引失败", 2);

    private final String text;

    private final int value;

    PictureIndexStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static PictureIndexStatusEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (PictureIndexStatusEnum statusEnum : PictureIndexStatusEnum.values()) {
            if (statusEnum.value == value) {
                return statusEnum;
            }
        }
        return null;
    }
}

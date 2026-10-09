package com.flipped.spaceservice.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 空间成员状态枚举
 */
@Getter
public enum SpaceUserStatusEnum {

    /**
     * 已被邀请，等待用户接受
     */
    PENDING("邀请中", 0),

    /**
     * 已加入空间
     */
    JOINED("已加入", 1),

    /**
     * 用户拒绝了邀请
     */
    REJECTED("已拒绝", 2);

    private final String text;

    private final int value;

    SpaceUserStatusEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static SpaceUserStatusEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (SpaceUserStatusEnum statusEnum : SpaceUserStatusEnum.values()) {
            if (statusEnum.value == value) {
                return statusEnum;
            }
        }
        return null;
    }
}

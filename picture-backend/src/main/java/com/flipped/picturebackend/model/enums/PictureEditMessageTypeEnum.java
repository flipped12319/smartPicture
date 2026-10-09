package com.flipped.picturebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片协同编辑的消息类型
 * <p>
 * 六类消息覆盖了一个用户参与编辑的完整生命周期：
 * 加入 → 开始编辑 → 编辑操作 → 退出编辑 → 断开连接，另有错误消息兜底。
 */
@Getter
public enum PictureEditMessageTypeEnum {

    /**
     * 1. 建立连接，加入编辑
     */
    INFO("建立连接，加入编辑", 1),

    /**
     * 2. 开始对图片进行编辑，进入编辑状态
     */
    START("开始编辑", 2),

    /**
     * 3. 对图片进行了编辑操作
     */
    OPERATION("编辑操作", 3),

    /**
     * 4. 退出编辑状态
     */
    EXIT("退出编辑状态", 4),

    /**
     * 5. 断开连接，离开编辑
     */
    LEAVE("断开连接，离开编辑", 5),

    /**
     * 6. 发送了错误的消息
     */
    ERROR("消息错误", 6);

    private final String text;

    private final int value;

    PictureEditMessageTypeEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static PictureEditMessageTypeEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (PictureEditMessageTypeEnum typeEnum : PictureEditMessageTypeEnum.values()) {
            if (typeEnum.value == value) {
                return typeEnum;
            }
        }
        return null;
    }
}

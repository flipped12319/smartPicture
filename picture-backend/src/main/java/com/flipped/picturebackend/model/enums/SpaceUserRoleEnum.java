package com.flipped.picturebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 空间成员角色枚举
 * <p>
 * value 越大权限越高，且高权限包含低权限的全部能力，因此可以直接用
 * {@code role.getValue() >= requireRole.getValue()} 判断是否满足要求。
 */
@Getter
public enum SpaceUserRoleEnum {

    /**
     * 只能看
     */
    VIEWER("只能看", 0),

    /**
     * 能看、能上传
     */
    UPLOADER("能看能上传", 1),

    /**
     * 能看、能上传、能编辑（含删除）
     */
    EDITOR("能看能上传还能编辑", 2),

    /**
     * 空间管理员（空间创建者）
     */
    MANAGER("管理员", 3);

    private final String text;

    private final int value;

    SpaceUserRoleEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static SpaceUserRoleEnum getEnumByValue(Integer value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (SpaceUserRoleEnum roleEnum : SpaceUserRoleEnum.values()) {
            if (roleEnum.value == value) {
                return roleEnum;
            }
        }
        return null;
    }

    /**
     * 判断角色是否满足最低权限要求
     *
     * @param role        用户在该空间中的角色，可为 null（表示不是成员）
     * @param requireRole 要求的最低角色
     */
    public static boolean hasPermission(SpaceUserRoleEnum role, SpaceUserRoleEnum requireRole) {
        if (role == null || requireRole == null) {
            return false;
        }
        return role.getValue() >= requireRole.getValue();
    }
}

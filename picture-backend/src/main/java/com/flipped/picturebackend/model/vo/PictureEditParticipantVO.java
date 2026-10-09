package com.flipped.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 协同编辑的参与者信息（发给前端展示）
 */
@Data
public class PictureEditParticipantVO implements Serializable {

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 该用户当前是否处于编辑状态
     */
    private Boolean editing;

    private static final long serialVersionUID = 1L;
}

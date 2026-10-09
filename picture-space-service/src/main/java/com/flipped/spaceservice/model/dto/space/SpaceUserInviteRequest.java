package com.flipped.spaceservice.model.dto.space;

import lombok.Data;

import java.io.Serializable;

/**
 * 邀请用户加入团队空间请求
 */
@Data
public class SpaceUserInviteRequest implements Serializable {

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 被邀请人的用户账号
     */
    private String userAccount;

    /**
     * 赋予的空间角色：0-只读 1-可上传 2-可编辑
     */
    private Integer spaceRole;

    private static final long serialVersionUID = 1L;
}

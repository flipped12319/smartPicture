package com.flipped.picturebackend.model.dto.space;

import com.flipped.picturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 空间成员查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SpaceUserQueryRequest extends PageRequest implements Serializable {

    /**
     * 空间成员记录 id
     */
    private Long id;

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 空间角色：0-只读 1-可上传 2-可编辑 3-管理员
     */
    private Integer spaceRole;

    /**
     * 状态：0-邀请中 1-已加入 2-已拒绝
     */
    private Integer status;

    private static final long serialVersionUID = 1L;
}

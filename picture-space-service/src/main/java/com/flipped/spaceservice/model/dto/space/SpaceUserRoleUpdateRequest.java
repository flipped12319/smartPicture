package com.flipped.spaceservice.model.dto.space;

import lombok.Data;

import java.io.Serializable;

/**
 * 修改空间成员角色请求
 */
@Data
public class SpaceUserRoleUpdateRequest implements Serializable {

    /**
     * 空间成员记录 id
     */
    private Long id;

    /**
     * 新的空间角色：0-只读 1-可上传 2-可编辑
     */
    private Integer spaceRole;

    private static final long serialVersionUID = 1L;
}

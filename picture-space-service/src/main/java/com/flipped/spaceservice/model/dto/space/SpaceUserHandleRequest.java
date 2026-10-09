package com.flipped.spaceservice.model.dto.space;

import lombok.Data;

import java.io.Serializable;

/**
 * 处理空间邀请请求（接受 / 拒绝 / 移除成员）
 */
@Data
public class SpaceUserHandleRequest implements Serializable {

    /**
     * 空间成员记录 id
     */
    private Long id;

    private static final long serialVersionUID = 1L;
}

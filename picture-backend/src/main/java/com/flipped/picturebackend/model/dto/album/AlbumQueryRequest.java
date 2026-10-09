package com.flipped.picturebackend.model.dto.album;

import com.flipped.picturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 相册查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AlbumQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 所属私人空间 id
     */
    private Long spaceId;

    /**
     * 相册名称
     */
    private String name;

    private static final long serialVersionUID = 1L;
}

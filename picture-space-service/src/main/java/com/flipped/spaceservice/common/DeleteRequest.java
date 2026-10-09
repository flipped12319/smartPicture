package com.flipped.spaceservice.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用删除请求（只有 id）。
 * 与单体同名同结构，保证前端传的 JSON 不用改。
 */
@Data
public class DeleteRequest implements Serializable {

    private Long id;

    private static final long serialVersionUID = 1L;
}

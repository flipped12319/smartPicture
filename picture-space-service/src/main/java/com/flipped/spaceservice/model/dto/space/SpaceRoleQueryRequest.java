package com.flipped.spaceservice.model.dto.space;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 内部接口：批量查询「某用户在一组空间中的角色」
 * <p>
 * 字段全部按「可缺省」设计（阶段 3 的教训）：调用方传 null / 空集合时返回空结果，
 * 而不是抛 422/400 让调用方去猜。
 */
@Data
public class SpaceRoleQueryRequest implements Serializable {

    /**
     * 用户 id（必填，缺失时返回空结果）
     */
    private Long userId;

    /**
     * 空间 id 列表；为空时返回空结果
     */
    private List<Long> spaceIds;

    private static final long serialVersionUID = 1L;
}

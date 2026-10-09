package com.flipped.spaceservice.common;

import lombok.Data;

@Data
public class PageRequest {

    private int current = 1;

    private int pageSize = 10;

    private String sortField;

    /**
     * 排序顺序（默认降序）
     */
    private String sortOrder = "descend";
}

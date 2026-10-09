package com.flipped.spaceservice.model.dto.space;

import lombok.Data;

import java.io.Serializable;

/**
 * 内部接口：调整空间配额（阶段 4 的跨服务写）
 * <p>
 * 刻意用「增量」而不是绝对值：多个实例并发上传时，
 * 传绝对值会造成互相覆盖（A 读到 100、B 读到 100，各自 +10 后写 110，实际应为 120）。
 * 增量由 SQL 侧原子累加，调用方不需要先读后写。
 * <p>
 * 所有字段都按「可缺省」设计（阶段 3 的教训）：缺省时按 0 处理，而不是让请求 422/400。
 */
@Data
public class SpaceQuotaChangeRequest implements Serializable {

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 容量增量（字节），可为负
     */
    private Long sizeDelta;

    /**
     * 数量增量，可为负
     */
    private Long countDelta;

    /**
     * 变更原因，仅用于日志与对账
     */
    private String reason;

    private static final long serialVersionUID = 1L;
}

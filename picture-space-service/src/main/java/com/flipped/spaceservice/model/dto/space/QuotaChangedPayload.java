package com.flipped.spaceservice.model.dto.space;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 额度变更消息体（阶段 5d-b，与单体侧 {@code QuotaChangedPayload} 逐字段一致）
 *
 * <p>用「增量」而不是「绝对值」：增量天然是幂等的补丁，适合「重复投递 + 去重」这套语义；
 * 绝对值则要求消费端知道「这条是不是最新的」，需要额外版本号。代价是消费端
 * **必须有幂等去重**（本服务的 {@code consumed_message}），否则重复消费会扣两次。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaChangedPayload implements Serializable {

    private Long spaceId;

    private Long sizeDelta;

    private Long countDelta;

    private String reason;

    private static final long serialVersionUID = 1L;

    public long sizeDeltaOrZero() {
        return sizeDelta == null ? 0L : sizeDelta;
    }

    public long countDeltaOrZero() {
        return countDelta == null ? 0L : countDelta;
    }
}

package com.flipped.picturebackend.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 额度变更消息体（阶段 5d-b，对应 §4.6 的 {@code quota.changed}）
 *
 * <p><b>为什么字段全部允许缺省</b>（阶段 3 的 422 教训）：消费端解析不出来时应记录并进死信，
 * 而不是因为一个字段缺失就整批失败。{@code sizeDelta}/{@code countDelta} 缺省按 0 处理。
 *
 * <p><b>为什么用「增量」而不是「绝对值」</b>：
 * 增量天然是幂等的补丁，适合「重复投递 + 去重」这套语义；
 * 绝对值则要求消费端知道「这条是不是最新的」，需要额外版本号，复杂度高得多。
 * 代价是消费端必须有幂等去重（本项目的 {@code consumed_message} 表），否则重复消费会扣两次 ——
 * 这也是为什么 5d 必须排在 5a/5c 之后：地基先行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaChangedPayload implements Serializable {

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 容量增量（可为负）
     */
    private Long sizeDelta;

    /**
     * 数量增量（可为负）
     */
    private Long countDelta;

    /**
     * 仅用于日志与对账，便于定位是哪条业务链路造成的偏差
     */
    private String reason;

    private static final long serialVersionUID = 1L;

    public long sizeDeltaOrZero() {
        return sizeDelta == null ? 0L : sizeDelta;
    }

    public long countDeltaOrZero() {
        return countDelta == null ? 0L : countDelta;
    }
}

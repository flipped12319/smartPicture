package com.flipped.spaceservice.model.dto.space;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 配额预占结果（阶段 5d-b）
 *
 * <p><b>为什么需要它，而不是直接返回 Space 或抛异常</b>：
 * 预占失败有两种，调用方要区别对待，而且**都不能当成系统故障**：
 * <ul>
 *     <li>空间不存在 -> 调用方报 40400；</li>
 *     <li>额度不足 -> 调用方报「空间条数不足 / 空间大小不足」，用户看到的是明确业务提示。</li>
 * </ul>
 * 如果一律抛异常，就会和「space-service 连不上」混在一起（正是阶段 4 踩过的坑：
 * 把 null 当成两种语义）。所以这里用「结构化的结果」表达业务判定，
 * 异常只留给**真正的故障**。
 *
 * <p>{@code limited} 为空表示预占成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaReserveResult implements Serializable {

    /**
     * 是否因额度不足而失败；null 表示成功
     */
    private String limited;

    /**
     * 预占成功后的空间快照（失败时为 null）
     */
    private com.flipped.spaceservice.model.entity.Space space;

    private static final long serialVersionUID = 1L;

    public static QuotaReserveResult ok(com.flipped.spaceservice.model.entity.Space space) {
        return new QuotaReserveResult(null, space);
    }

    public static QuotaReserveResult limited(String reason) {
        return new QuotaReserveResult(reason, null);
    }
}

package com.flipped.picturebackend.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 索引请求消息体（阶段 5b，对应 §4.2 的 {@code picture.index.requested}）
 *
 * <p><b>为什么只带 pictureId 和「是否回填」，不带 url / spaceId / userId</b>：
 * 文档 §4.2 原方案建议把 url、spaceId、userId 一起塞进消息，理由是消费者不用回查数据库。
 * 这里刻意不这么做：
 * <ul>
 *     <li>消息会**在队列里停留**（削峰正是它的目的）。停留期间图片可能被改名、被改成
 *         「仅自己可见」、甚至被删除 —— 带着快照的 url 去索引，索引的就是过期数据；</li>
 *     <li>消费者手里有 pictureId，回查一次数据库拿到的是**当时最新**的状态，
 *         顺带天然处理「图片已被删除」（查不到就跳过并 ack）；</li>
 *     <li>消息体越小，DLQ 里堆积和重放的代价越低。</li>
 * </ul>
 * 代价是每条消息多一次主键查询 —— 相对于「调多模态模型几十秒」，可以忽略。
 *
 * <p>字段一律按**可缺省**设计（阶段 3 的 422 教训）：消费端解析不出来时记录并进死信，
 * 而不是因为一个字段缺失整批失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureIndexPayload implements Serializable {

    /**
     * 要建索引的图片 id
     */
    private Long pictureId;

    /**
     * 是否把模型生成的内容回填到图片表（仅「审核通过」时为 true）
     */
    private Boolean fillBlankFields;

    private static final long serialVersionUID = 1L;

    public boolean shouldFillBlankFields() {
        // 缺省即 false：老消息或漏字段时宁可只建索引、不改用户的图片信息
        return Boolean.TRUE.equals(fillBlankFields);
    }
}

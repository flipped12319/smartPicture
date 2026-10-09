package com.flipped.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 本地消息表（阶段 5c）
 *
 * <p><b>它解决的问题</b>：跨服务写库与发消息**不可能放在同一个事务里**。
 * 直接发消息的话，存在两个必然出现的坏窗口：
 * <ol>
 *     <li>业务事务提交了，但消息还没发出去（进程崩、MQ 挂）→ 消息**永远丢了**；</li>
 *     <li>消息发出去了，但业务事务回滚 → 消费方做了一件不该做的事。</li>
 * </ol>
 * 本地消息表的做法是把「要发的消息」当成业务数据的一部分，**在同一个本地事务里落库**
 * （于是"业务成功"与"消息存在"原子一致），再由定时任务扫描未投递的记录补投。
 * 消费端靠 {@code consumed_message} 幂等表消化重复投递，于是整个链路变成「最终一致」。
 *
 * <p>⚠️ 字段名与列名保持一致（本项目 {@code map-underscore-to-camel-case: false}）。
 */
@Data
@TableName("local_message")
public class LocalMessage implements Serializable {

    /**
     * 自增主键。**消息 id 不用它** —— 幂等键是 {@code messageId}（UUID）。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 全局唯一消息 id，与 {@code consumed_message.messageId} 对应。
     * <p>
     * 必须是**生产端生成**的，且补投时不能变 —— 一变，消费端的幂等去重就完全失效了。
     */
    private String messageId;

    /**
     * 消息类型，如 {@code picture.index.requested}
     */
    private String messageType;

    /**
     * 目标交换机
     */
    private String exchange;

    /**
     * 路由键
     */
    private String routingKey;

    /**
     * 消息体（JSON）
     */
    private String payload;

    /**
     * 0-待投递 1-已投递 2-已确认 3-超限待人工
     */
    private Integer status;

    /**
     * 已投递次数（用于退避与超限判定）
     */
    private Integer retryCount;

    /**
     * 最后一次失败原因
     */
    private String lastError;

    /**
     * 下次可投递时间（退避）。为 null 表示可以立刻投递。
     */
    private Date nextRetryAt;

    private Date createTime;

    private Date updateTime;

    private static final long serialVersionUID = 1L;
}

package com.flipped.picturebackend.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消息消费幂等表 Mapper（阶段 5a）
 *
 * <p>刻意手写 SQL 而不是用 MyBatis-Plus 的 {@code BaseMapper}：
 * {@code consumed_message} 的主键是 **(messageId, consumer) 复合主键**，
 * 而 MyBatis-Plus 的 {@code @TableId} 只支持单主键 —— 用它就没法表达
 * 「同一条消息被不同消费者各处理一次」这个语义。
 *
 * <p>核心是 {@link #tryConsume} 的 <b>INSERT IGNORE</b>：
 * 插入成功返回 1 → 第一次见到这条消息；影响行数 0（唯一键冲突）→ 重复投递。
 * 用它而不是「先 select 再 insert」，是因为后者有竞态：两个消费者可能同时查不到，
 * 然后**都去执行副作用** —— 对配额扣减这种操作就是直接的数据错误。
 */
@Mapper
public interface ConsumedMessageMapper {

    /**
     * 尝试登记一次消费。
     *
     * @return 1 = 首次消费（可以继续执行业务）；0 = 重复投递（应直接 ack 丢弃）
     */
    @Insert("INSERT IGNORE INTO consumed_message (messageId, consumer, messageType, createdAt) "
            + "VALUES (#{messageId}, #{consumer}, #{messageType}, NOW())")
    int tryConsume(@Param("messageId") String messageId,
                   @Param("consumer") String consumer,
                   @Param("messageType") String messageType);

    /**
     * 撤销登记：业务处理失败时调用。
     * <p>
     * 必须撤销，否则「第一次消费失败 → 重试投递」会被当成重复消息直接丢弃，
     * 消息就永远丢了（幂等表的目的是防重复执行**成功**的副作用，
     * 不是把失败也当成处理过）。
     */
    @Insert("DELETE FROM consumed_message WHERE messageId = #{messageId} AND consumer = #{consumer}")
    int release(@Param("messageId") String messageId, @Param("consumer") String consumer);
}

package com.flipped.spaceservice.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消息消费幂等表 Mapper（阶段 5d）
 *
 * <p>与单体侧那份**结构相同、刻意各留一份**（本项目不用共享代码模块，见 MqMessage 的注释）。
 * 表 {@code consumed_message} 是共享的（同一个库），主键是 (messageId, consumer) 复合键，
 * 所以这里手写 SQL 而不是用 MyBatis-Plus 的 BaseMapper（它只支持单主键）。
 *
 * <p>核心是 INSERT IGNORE：插入成功 = 第一次见到这条消息；影响 0 行 = 重复投递。
 * 用它而不是「先 select 再 insert」，因为后者有竞态 —— 对配额扣减这种操作就是直接的数据错误。
 */
@Mapper
public interface ConsumedMessageMapper {

    /**
     * @return 1 = 首次消费；0 = 重复投递（应直接 ack 丢弃）
     */
    @Insert("INSERT IGNORE INTO consumed_message (messageId, consumer, messageType, createdAt) "
            + "VALUES (#{messageId}, #{consumer}, #{messageType}, NOW())")
    int tryConsume(@Param("messageId") String messageId,
                   @Param("consumer") String consumer,
                   @Param("messageType") String messageType);

    /**
     * 撤销登记：业务处理失败时调用。
     * <p>
     * 必须撤销，否则「第一次消费失败 → 重投」会被当成重复消息直接丢弃，消息就永远丢了。
     * 幂等表防的是「重复执行**成功**的副作用」，不是把失败也当成处理过。
     */
    @Insert("DELETE FROM consumed_message WHERE messageId = #{messageId} AND consumer = #{consumer}")
    int release(@Param("messageId") String messageId, @Param("consumer") String consumer);
}

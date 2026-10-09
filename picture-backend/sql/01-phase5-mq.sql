-- ============================================================
-- 阶段 5 增量迁移：消息基础设施所需的两张表
-- ------------------------------------------------------------
-- 用法（对已有库执行，**不动任何现有表**）：
--     mysql -uroot -p database1 < picture-backend/sql/01-phase5-mq.sql
--
-- ⚠️ 为什么单独一个文件，而不是加进 00-schema.sql：
--   `00-schema.sql` 是 mysqldump 出来的**整库快照**，每个表前面都带 DROP TABLE ——
--   它是「重建一个空库」用的，对已有数据的库执行会直接清库。
--   本文件是本阶段真正要执行的迁移，只做 CREATE TABLE IF NOT EXISTS，可反复执行。
-- ============================================================

-- ------------------------------------------------------------
-- consumed_message：消费幂等表（阶段 5a 的通用机制）
-- ------------------------------------------------------------
-- 为什么必须有它：MQ 的语义是「至少一次」——同一条消息可能被投递多次
-- （消费者 ack 丢失、重试、DLQ 重放都会导致重复）。而有些副作用绝不能重复执行：
--   配额扣减（totalSize = totalSize + Δ）重复一次就少一份额度；
--   索引重建重复一次只是浪费，可以容忍；
--   通知/发券这类则完全不能重复。
-- 所以统一在「消费入口」做去重：insert 成功 = 第一次见到这条消息，可以放行；
-- insert 撞唯一键 = 重复投递，直接 ack 丢弃。
--
-- 用「insert 唯一键冲突」而不是「先 select 再 insert」：后者有竞态，
-- 两个消费者可能同时查不到、然后都去执行副作用。
--
-- 注意：这张表会一直长。阶段 5 先不做清理（消息量级很小），
-- 真要上量时按 created_at 定期删除（保留 7 天足够覆盖任何重投窗口）。
CREATE TABLE IF NOT EXISTS `consumed_message` (
  `messageId`   varchar(64)  NOT NULL COMMENT '消息全局唯一 id（生产端生成）',
  `consumer`    varchar(128) NOT NULL COMMENT '消费者标识：队列名或监听器名，便于同一消息被多个消费者各消费一次',
  `messageType` varchar(64)  DEFAULT NULL COMMENT '消息类型，排查用',
  `createdAt`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次消费时间',
  PRIMARY KEY (`messageId`, `consumer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息消费幂等表（阶段 5）';

-- ------------------------------------------------------------
-- local_message：本地消息表（阶段 5c 用，5a 先建好）
-- ------------------------------------------------------------
-- 解决的是「业务写库成功、但消息没发出去」这个窗口：
--   业务事务里**同时**写业务数据和一条待发送的消息（同一个本地事务 → 原子）；
--   事务提交后由定时任务扫描 status=0 的记录去投递，投递成功置 1。
-- 这样即使 RabbitMQ 挂了、进程崩了，消息也不会丢，恢复后自动补投。
--
-- 阶段 6 的 `picture.changed`（缓存广播）与 `quota.changed`（配额）都复用它。
CREATE TABLE IF NOT EXISTS `local_message` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '自增主键（消息 id 用 UUID，不用它）',
  `messageId`   varchar(64)  NOT NULL COMMENT '全局唯一 id，与 consumed_message.messageId 对应',
  `messageType` varchar(64)  NOT NULL COMMENT '如 picture.index.requested / quota.changed',
  `exchange`    varchar(128) NOT NULL COMMENT '目标交换机',
  `routingKey`  varchar(128) NOT NULL COMMENT '路由键',
  `payload`     text         NOT NULL COMMENT '消息体（JSON）',
  -- 0-待投递 1-已投递 2-已确认（消费成功） 3-投递失败超限，需人工
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0待投递 1已投递 2已确认 3超限待人工',
  `retryCount`  int          NOT NULL DEFAULT 0 COMMENT '已投递次数',
  `lastError`   varchar(500) DEFAULT NULL COMMENT '最后一次失败原因',
  `nextRetryAt` datetime     DEFAULT NULL COMMENT '下次可投递时间（退避）',
  `createTime`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updateTime`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_messageId` (`messageId`),
  -- 扫描待投递记录用：status + nextRetryAt
  KEY `idx_status_retry` (`status`, `nextRetryAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表（阶段 5c）';

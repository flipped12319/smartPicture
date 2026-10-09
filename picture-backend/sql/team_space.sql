-- ============================================================
-- 团队空间功能建表脚本
-- 在启动后端之前，请先在 MySQL 中执行本脚本
-- 数据库：application.yml 中配置的 database1
-- ============================================================

-- ----------------------------
-- 1. space 表新增「空间类型」字段
--    0-私有空间（每个用户仅一个）；1-团队空间（可创建多个，可邀请成员）
--    注意：本 ALTER 语句只需执行一次，重复执行会提示字段已存在
-- ----------------------------
ALTER TABLE `space`
    ADD COLUMN `spaceType` TINYINT NOT NULL DEFAULT 0 COMMENT '空间类型：0-私有 1-团队';

-- ----------------------------
-- 2. 空间成员表
--    同时承载三种状态：邀请中 / 已加入 / 已拒绝
--    这是一张关联表，使用物理删除（无 isDelete 字段），
--    以便依赖 (spaceId, userId) 唯一索引防止重复成员，并支持移除后重新邀请。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `space_user`
(
    `id`         BIGINT   NOT NULL COMMENT 'id',
    `spaceId`    BIGINT   NOT NULL COMMENT '空间 id',
    `userId`     BIGINT   NOT NULL COMMENT '用户 id',
    `spaceRole`  TINYINT  NOT NULL DEFAULT 0 COMMENT '空间角色：0-只读 1-可上传 2-可编辑 3-管理员',
    `status`     TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0-邀请中 1-已加入 2-已拒绝',
    `inviterId`  BIGINT   NULL COMMENT '邀请人 id',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_spaceId_userId` (`spaceId`, `userId`),
    KEY `idx_userId_status` (`userId`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='空间成员';

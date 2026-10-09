-- ============================================================
-- 相册功能建表脚本
-- 在启动后端之前，请先在 MySQL 中执行本脚本
-- 数据库：application.yml 中配置的 database1
-- ============================================================

-- ----------------------------
-- 相册表
-- 说明：相册内的图片数量与封面由 album_picture 关联表实时统计得出，
--      因此这里不冗余存储 pictureCount / coverUrl，避免数据不一致。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `album`
(
    `id`           BIGINT       NOT NULL COMMENT 'id',
    `name`         VARCHAR(64)  NOT NULL COMMENT '相册名称',
    `introduction` VARCHAR(512) NOT NULL COMMENT '相册说明',
    `userId`       BIGINT       NOT NULL COMMENT '创建用户 id',
    `spaceId`      BIGINT       NULL COMMENT '所属私人空间 id',
    `createTime`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `editTime`     DATETIME     NULL COMMENT '编辑时间',
    `updateTime`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_userId` (`userId`),
    KEY `idx_spaceId` (`spaceId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='相册';

-- ----------------------------
-- 相册图片关联表
-- 注意：这是一张纯关联表，使用物理删除（没有 isDelete 字段），
--      这样可以依赖 (albumId, pictureId) 唯一索引防止重复关联。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `album_picture`
(
    `id`         BIGINT   NOT NULL COMMENT 'id',
    `albumId`    BIGINT   NOT NULL COMMENT '相册 id',
    `pictureId`  BIGINT   NOT NULL COMMENT '图片 id',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_albumId_pictureId` (`albumId`, `pictureId`),
    KEY `idx_pictureId` (`pictureId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='相册图片关联';

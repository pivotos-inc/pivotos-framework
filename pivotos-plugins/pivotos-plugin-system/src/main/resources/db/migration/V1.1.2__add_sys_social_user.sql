-- =============================================================
-- V1.1.2：sys_social_user 三方社交账号绑定表（S16 移动端登录体系）
-- 定位：平台级共享表（与 sys_user 同级，tenant Starter 内置忽略表已登记）
-- 约定：id 雪花（禁自增）、审计字段四件套、deleted 逻辑删除、无外键
-- 唯一约束：(channel, open_id) 一个三方身份只能绑一个系统用户
-- =============================================================

CREATE TABLE sys_social_user (
                                 id          BIGINT       NOT NULL COMMENT '主键（雪花）',
                                 user_id     BIGINT       NOT NULL COMMENT '系统用户ID（sys_user.id）',
                                 channel     VARCHAR(32)  NOT NULL COMMENT '渠道（wechat-mini / alipay-mini）',
                                 open_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '渠道内用户标识（openid）',
                                 union_id    VARCHAR(64)  DEFAULT NULL COMMENT '开放平台 unionId（跨小程序打通，可空）',
                                 create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                                 create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                                 update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                                 update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                                 deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
                                 PRIMARY KEY (id),
                                 UNIQUE KEY uk_channel_openid (channel, open_id),
                                 KEY idx_user_id (user_id),
                                 KEY idx_union_id (union_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '三方社交账号绑定表';

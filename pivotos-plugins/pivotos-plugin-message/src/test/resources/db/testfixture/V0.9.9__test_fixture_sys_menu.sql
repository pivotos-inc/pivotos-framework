-- =============================================================
-- message 插件测试夹具：最小 sys_menu（菜单 SQL V1.1.1 的宿主表）
-- 仅测试库使用（db/testfixture 先于 db/migration 执行）；
-- 真实环境 sys_menu 由 system 插件 V1.0.0 创建，本脚本不参与生产部署
-- =============================================================
CREATE TABLE IF NOT EXISTS sys_menu (
                                        id          BIGINT       NOT NULL COMMENT '菜单ID（雪花）',
                                        parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID（0为根）',
                                        menu_name   VARCHAR(64)  NOT NULL COMMENT '菜单名称',
                                        menu_type   CHAR(1)      NOT NULL COMMENT '类型（M目录 C菜单 F按钮）',
                                        path        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '路由地址',
                                        component   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '组件路径',
                                        perms       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '权限标识',
                                        icon        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '图标',
                                        sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
                                        visible     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可见（0显示 1隐藏）',
                                        status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                                        create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                                        create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                                        update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                                        update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                                        deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                                        PRIMARY KEY (id),
                                        KEY idx_parent_id (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '菜单权限表（测试夹具）';

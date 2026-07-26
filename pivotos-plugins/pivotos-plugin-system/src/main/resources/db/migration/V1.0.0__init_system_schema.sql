-- =============================================================
-- PivotOS system 插件 · 库表结构
-- 约定：id 雪花（禁自增）、审计字段四件套、deleted 逻辑删除、无外键
-- =============================================================

-- 部门表
CREATE TABLE sys_dept (
                          id          BIGINT       NOT NULL COMMENT '部门ID（雪花）',
                          parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门ID（0为根）',
                          dept_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '部门名称',
                          ancestors   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '祖级列表，逗号分隔，如 0,1,2',
                          leader_id   BIGINT       DEFAULT NULL COMMENT '负责人用户ID',
                          sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
                          status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                          create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                          create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                          update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                          update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                          deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记（0正常 1删除）',
                          PRIMARY KEY (id),
                          KEY idx_parent_id (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '部门表';

-- 岗位表（S7 仅建表，服务/页面 P1 补）
CREATE TABLE sys_post (
                          id          BIGINT       NOT NULL COMMENT '岗位ID（雪花）',
                          post_code   VARCHAR(64)  NOT NULL COMMENT '岗位编码',
                          post_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '岗位名称',
                          sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
                          status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                          remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
                          create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                          create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                          update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                          update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                          deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_post_code (post_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '岗位表';

-- 用户表
CREATE TABLE sys_user (
                          id          BIGINT       NOT NULL COMMENT '用户ID（雪花）',
                          username    VARCHAR(64)  NOT NULL COMMENT '用户名',
                          nickname    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
                          password    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '密码（BCrypt）',
                          dept_id     BIGINT       DEFAULT NULL COMMENT '部门ID',
                          email       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '邮箱',
                          mobile      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '手机号',
                          gender      TINYINT      NOT NULL DEFAULT 0 COMMENT '性别（0未知 1男 2女）',
                          avatar      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像地址',
                          status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                          remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
                          create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                          create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                          update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                          update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                          deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_username (username),
                          KEY idx_dept_id (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';
-- 注意：username 物理唯一。逻辑删除的用户名不能直接复用（服务层查重不含已删行，
-- 复用需先物理清理）。此取舍与 RuoYi 一致，如有更高要求后续改为 uk(username, deleted)。

-- 角色表
CREATE TABLE sys_role (
                          id          BIGINT       NOT NULL COMMENT '角色ID（雪花）',
                          role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
                          role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码（如 super_admin）',
                          sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
                          status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                          remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
                          create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                          create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                          update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                          update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                          deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色表';

-- 菜单/权限表
CREATE TABLE sys_menu (
                          id          BIGINT       NOT NULL COMMENT '菜单ID（雪花）',
                          parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID（0为根）',
                          menu_name   VARCHAR(64)  NOT NULL COMMENT '菜单名称',
                          menu_type   CHAR(1)      NOT NULL COMMENT '类型（M目录 C菜单 F按钮）',
                          path        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '路由地址',
                          component   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '组件路径',
                          perms       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '权限标识（如 system:user:list）',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '菜单权限表';

-- 用户-角色关联
CREATE TABLE sys_user_role (
                               user_id BIGINT NOT NULL COMMENT '用户ID',
                               role_id BIGINT NOT NULL COMMENT '角色ID',
                               PRIMARY KEY (user_id, role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户角色关联表';

-- 用户-岗位关联
CREATE TABLE sys_user_post (
                               user_id BIGINT NOT NULL COMMENT '用户ID',
                               post_id BIGINT NOT NULL COMMENT '岗位ID',
                               PRIMARY KEY (user_id, post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户岗位关联表';

-- 角色-菜单关联
CREATE TABLE sys_role_menu (
                               role_id BIGINT NOT NULL COMMENT '角色ID',
                               menu_id BIGINT NOT NULL COMMENT '菜单ID',
                               PRIMARY KEY (role_id, menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色菜单关联表';

-- 字典类型表
CREATE TABLE sys_dict_type (
                               id          BIGINT       NOT NULL COMMENT '字典ID（雪花）',
                               dict_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '字典名称',
                               dict_type   VARCHAR(64)  NOT NULL COMMENT '字典类型（如 sys_common_status）',
                               status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                               remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
                               create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                               create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                               update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                               update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                               deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                               PRIMARY KEY (id),
                               UNIQUE KEY uk_dict_type (dict_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '字典类型表';

-- 字典数据表
CREATE TABLE sys_dict_data (
                               id          BIGINT       NOT NULL COMMENT '字典数据ID（雪花）',
                               dict_type   VARCHAR(64)  NOT NULL COMMENT '字典类型',
                               dict_label  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '字典标签',
                               dict_value  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '字典键值',
                               sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
                               status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态（0正常 1停用）',
                               remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
                               create_by   BIGINT       DEFAULT NULL COMMENT '创建人',
                               create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
                               update_by   BIGINT       DEFAULT NULL COMMENT '更新人',
                               update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
                               deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                               PRIMARY KEY (id),
                               KEY idx_dict_type (dict_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '字典数据表';

-- 参数配置表
CREATE TABLE sys_config (
                            id           BIGINT       NOT NULL COMMENT '参数ID（雪花）',
                            config_name  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '参数名称',
                            config_key   VARCHAR(128) NOT NULL COMMENT '参数键名',
                            config_value VARCHAR(512) NOT NULL DEFAULT '' COMMENT '参数键值',
                            config_type  CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '内置标记（Y系统内置 N自定义）',
                            remark       VARCHAR(500) DEFAULT NULL COMMENT '备注',
                            create_by    BIGINT       DEFAULT NULL COMMENT '创建人',
                            create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
                            update_by    BIGINT       DEFAULT NULL COMMENT '更新人',
                            update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
                            deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '删除标记',
                            PRIMARY KEY (id),
                            UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '参数配置表';

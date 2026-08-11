-- =====================================================
-- Flyway 迁移: ${functionName} — ${tableName}
-- =====================================================

CREATE TABLE IF NOT EXISTS `${tableName}` (
    `id`              BIGINT        NOT NULL COMMENT '编号',
<#list columns as col>
<#if col.javaField != "id" && col.javaField != "createBy" && col.javaField != "createTime" && col.javaField != "updateBy" && col.javaField != "updateTime" && col.javaField != "deleted">
    `${col.columnName}` ${col.columnType} <#if col.isRequired == 1>NOT NULL<#else>DEFAULT NULL</#if> COMMENT '${col.columnComment!}',
</#if>
</#list>
    `create_by`       BIGINT        DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT        DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='${tableComment!}';
<#if hasSub>

-- =====================================================
-- 子表: ${subFunctionName} — ${subTableName}（S51 / 2.4-F3）
-- =====================================================

CREATE TABLE IF NOT EXISTS `${subTableName}` (
    `id`              BIGINT        NOT NULL COMMENT '编号',
<#list subColumns as col>
<#if col.javaField != "id" && col.javaField != "createBy" && col.javaField != "createTime" && col.javaField != "updateBy" && col.javaField != "updateTime" && col.javaField != "deleted">
    `${col.columnName}` ${col.columnType} <#if col.isRequired == 1>NOT NULL<#else>DEFAULT NULL</#if> COMMENT '${col.columnComment!}',
</#if>
</#list>
    `create_by`       BIGINT        DEFAULT NULL COMMENT '创建者',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`       BIGINT        DEFAULT NULL COMMENT '更新者',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0正常 1删除）',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_${subFkColumn}` (`${subFkColumn}`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='${subTableComment!}';
</#if>

-- =====================================================
-- 菜单数据（系统工具 1100 下；id 取当前最大值 +10 起避让，
-- super_admin 走通配权限，无需 sys_role_menu 授权数据）
-- =====================================================
SET @gen_menu_id := (SELECT COALESCE(MAX(id), 0) + 10 FROM sys_menu);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (@gen_menu_id, 1100, '${functionName}', 'C', '${businessName}', '${moduleName}/${businessName}/index', '${moduleName}:${businessName}:list', 'tool', 9, 0, 0, 1, NOW(), 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort, visible, status, create_by, create_time, deleted) VALUES
    (@gen_menu_id + 1, @gen_menu_id, '${functionName}查询', 'F', '', '', '${moduleName}:${businessName}:query',  '', 1, 0, 0, 1, NOW(), 0),
    (@gen_menu_id + 2, @gen_menu_id, '${functionName}新增', 'F', '', '', '${moduleName}:${businessName}:add',    '', 2, 0, 0, 1, NOW(), 0),
    (@gen_menu_id + 3, @gen_menu_id, '${functionName}修改', 'F', '', '', '${moduleName}:${businessName}:edit',   '', 3, 0, 0, 1, NOW(), 0),
    (@gen_menu_id + 4, @gen_menu_id, '${functionName}删除', 'F', '', '', '${moduleName}:${businessName}:remove', '', 4, 0, 0, 1, NOW(), 0);

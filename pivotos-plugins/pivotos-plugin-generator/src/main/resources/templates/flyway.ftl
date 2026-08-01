-- =====================================================
-- Flyway 迁移: ${functionName} — ${tableName}
-- =====================================================

CREATE TABLE IF NOT EXISTS `${tableName}` (
    `id`              BIGINT        NOT NULL COMMENT '编号',
<#list insertColumns as col>
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

-- =====================================================
-- 菜单数据
-- =====================================================
INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `perms`, `menu_type`, `path`, `component`, `icon`, `sort`, `status`, `visible`)
VALUES
(
    UNIX_TIMESTAMP(NOW())*1000 + FLOOR(RAND()*1000),
    (SELECT id FROM sys_menu WHERE perms = 'system:${businessName}' LIMIT 1),
    '${functionName}',
    '${moduleName}:${businessName}:list',
    'C',
    '${businessName}',
    '${moduleName}/${businessName}/index',
    'system',
    1,
    1,
    1
);

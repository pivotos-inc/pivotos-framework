package com.pivotos.starter.datainspect.api.security;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * SQL 闸门上下文：所有「策略性」输入集中在此，闸门实现保持无状态、可单测。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
public class GuardContext {

    /** 期望行数（会被 hardMaxRows 收紧） */
    private int maxRows;

    /** 硬上限：请求参数不可突破 */
    private int hardMaxRows;

    /** 库表白名单（全小写表名，可带 schema 前缀 "schema.table"） */
    private Set<String> tableWhitelist;

    /** 是否允许查询任意表（false 时必须命中白名单） */
    private boolean allowAllTables;

    /** 当前租户 ID；null 表示无租户上下文（不改写） */
    private Long tenantId;

    /** 租户列名 */
    private String tenantIdColumn;

    /** 租户内置忽略表（这些表不追加租户条件） */
    private Set<String> tenantIgnoreTables;

    /** 是否强制租户改写（<b>默认 true，超管亦不豁免</b>） */
    private boolean forceTenantScope;

    /** 该表是否存在租户列（由调用方查元数据后传入；null 表示未知→按有处理） */
    private Boolean tableHasTenantColumn;
}

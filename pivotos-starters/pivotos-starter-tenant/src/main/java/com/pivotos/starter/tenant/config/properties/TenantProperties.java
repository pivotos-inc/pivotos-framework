package com.pivotos.starter.tenant.config.properties;

import com.pivotos.starter.tenant.strategy.TenantMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户配置（{@code pivotos.tenant.*}）。
 * 默认关闭；启用后按 mode 三选一装配隔离策略。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.tenant")
public class TenantProperties {

    /** 内置忽略表：sys_* 平台共享表（S14 设计评审 D1 决议）+ Flyway 历史表 */
    public static final Set<String> BUILTIN_IGNORE_TABLES = Set.of(
            "sys_dept", "sys_post", "sys_user", "sys_role", "sys_menu",
            "sys_user_role", "sys_user_post", "sys_role_menu",
            "sys_dict_type", "sys_dict_data", "sys_config",
            "flyway_schema_history");

    /** 总开关（条件装配锚点），默认关闭 */
    private boolean enabled = false;

    /** 隔离模式：column / schema / datasource，默认 column */
    private TenantMode mode = TenantMode.COLUMN;

    /** 请求头租户解析来源 */
    private String headerName = "X-Tenant-Id";

    /**
     * 追加的忽略表（column 模式行级过滤放行的表）。
     * 追加语义：与 {@link #BUILTIN_IGNORE_TABLES} 取并集生效。
     */
    private List<String> ignoreTables = new ArrayList<>();

    /** 不绑定租户上下文的接口（Ant 风格，如登录、平台管理接口） */
    private List<String> ignoreUrls = new ArrayList<>(List.of("/system/auth/login", "/system/auth/logout"));

    /** 严格模式：true 时启用后解析不到租户 → 403 拒绝（SaaS 强隔离场景） */
    private boolean strict = false;

    /** schema 模式：租户 ID → 数据源 key（key 约定 = schema 对应的数据源名） */
    private Map<Long, String> schemaMap = new ConcurrentHashMap<>();

    /** datasource 模式：租户 ID → 数据源 key */
    private Map<Long, String> datasourceMap = new ConcurrentHashMap<>();

    /**
     * 生效的忽略表全集：内置 + 用户追加
     */
    public Set<String> effectiveIgnoreTables() {
        Set<String> all = new LinkedHashSet<>(BUILTIN_IGNORE_TABLES);
        all.addAll(ignoreTables);
        return all;
    }
}

package com.pivotos.starter.datainspect.api.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据监控配置（pivotos.datainspect.*）。
 *
 * <p>这里刻意写成普通 POJO 不挂 {@code @ConfigurationProperties}——契约包保持零 Spring 依赖，
 * 由主 Starter 在 {@code @Bean} 方法上完成绑定（照抄 SearchProperties 的绑定姿势）。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
public class DataInspectProperties {

    /** 总开关；false 时所有组件一律降级 */
    private boolean enabled = true;

    /** 自由查询开关（高危）；生产建议 false */
    private boolean queryEnabled = true;

    /** 默认返回行数 */
    private int maxRows = 200;

    /** 硬上限：请求参数不可突破 */
    private int hardMaxRows = 1000;

    /** 单条语句最大字符数 */
    private int maxSqlLength = 4000;

    /** JDBC 查询超时（秒） */
    private int queryTimeoutSeconds = 5;

    /** 慢语句阈值（毫秒），超过打 WARN */
    private long slowThresholdMs = 1000;

    /**
     * 是否强制租户改写（默认 true）。
     *
     * <p><b>不提供豁免开关</b>：闸门是硬生效的安全边界，超管（{@code *:*:*}）同样被改写——
     * 租户语义属于安全过滤，与权限无关，二者不可互相替代。
     */
    private boolean forceTenantScope = true;

    /** 是否启用敏感列脱敏 */
    private boolean maskEnabled = true;

    /** 敏感列名正则 */
    private String maskColumnRegex = "(?i).*(password|passwd|pwd|secret|token|api_?key|private_?key|id_?card|bank|mobile|phone|email).*";

    private MySql mysql = new MySql();

    private Es es = new Es();

    private Redis redis = new Redis();

    /** MySQL 组件配置 */
    @Data
    public static class MySql {

        /** 是否允许查询任意表（false 时必须命中白名单——安全默认） */
        private boolean allowAllTables = false;

        /** 库表白名单，元素形如 "sys_user" 或 "pivotos_dev.sys_user" */
        private List<String> tableWhitelist = new ArrayList<>();

        /** 允许的 schema 白名单（为空则用当前库） */
        private List<String> schemaWhitelist = new ArrayList<>();

        /** 租户列名 */
        private String tenantIdColumn = "tenant_id";
    }

    /** ES 组件配置 */
    @Data
    public static class Es {

        private int maxRows = 100;

        /** 单次返回的索引/key 上限 */
        private int maxItems = 500;
    }

    /** Redis 组件配置 */
    @Data
    public static class Redis {

        /** SCAN 单次迭代数量 */
        private int scanCount = 500;

        /** 单次返回 key 上限 */
        private int maxKeys = 1000;

        /** 单次 value 截断字节 */
        private int valueTruncateBytes = 2048;
    }
}

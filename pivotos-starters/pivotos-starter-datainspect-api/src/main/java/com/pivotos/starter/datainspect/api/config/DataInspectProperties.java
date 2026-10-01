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

        /** 单次 _search 返回文档数上限（硬上限 1000，与服务端无关） */
        private int maxRows = 100;

        /** 单次返回的索引上限 */
        private int maxItems = 500;

        /** 单个字段值截断长度（字符） */
        private int valueTruncateBytes = 2048;
    }

    /** Redis 组件配置 */
    @Data
    public static class Redis {

        /** SCAN 单次迭代数量（COUNT 提示值，不是上限） */
        private int scanCount = 500;

        /**
         * SCAN 轮次上限（第一重保护：防大库长时间游走）。
         *
         * <p>一轮 = {@code scanCount} 个 key，因此最多遍历 {@code maxScanIterations * scanCount} 个 key
         *（默认 20 × 500 = 10000），与 {@code maxKeys}（返回上限，第二重保护）是两把独立的锁。
         */
        private int maxScanIterations = 20;

        /** 单次返回 key 上限（第二重保护：防结果集爆炸） */
        private int maxKeys = 1000;

        /** 单次 value 截断长度（字符，第三重保护：防大 value 拖垮渲染） */
        private int valueTruncateBytes = 2048;

        /** 未显式传 pattern 时使用的默认 pattern */
        private String defaultPattern = "*";

        /** 集合类型单页返回元素上限（hash/list/set/zset 预览） */
        private int maxElements = 100;
    }
}

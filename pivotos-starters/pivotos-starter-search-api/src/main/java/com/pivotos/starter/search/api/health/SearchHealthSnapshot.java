package com.pivotos.starter.search.api.health;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索 / ES 健康快照。
 * <p>由 {@code SearchHealthProvider} 采集，monitor 插件**原样透出**——monitor 只依赖契约包，
 * 不认识任何 ES 客户端类型（ArchUnit A1/A2：monitor 不得依赖 -es-java 实现模块）。
 *
 * <p><b>降级口径（硬性）</b>：simple / 未启用 / 连接不可达三种形态一律
 * {@code available=false} + {@code reason} 文案，**绝不抛异常**——监控页是只读旁路，
 * 它挂掉不应影响业务，也不应让前端拿到 500。
 *
 * <p>采集口径只用 ES 7.17 与 8.x/9.x 都支持的端点：
 * {@code _cluster/health} / {@code _cat/nodes} / {@code _cat/indices} / {@code _nodes/stats}，
 * 字段名取两个大版本交集（见 {@code EsHealthJsonParser} 的版本差异注释）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
public class SearchHealthSnapshot {

    // ---------- 可用性 ----------
    /** 是否拿到了真实 ES 指标（false 时只有 reason 有意义） */
    private boolean available;
    /** 当前<b>生效</b>的实现（simple / es-java / easy-es）；发生回落时为 simple */
    private String implementation;
    /** 配置值 {@code pivotos.search.type}（与 implementation 不同即发生了回落） */
    private String configuredType;
    /** 是否发生过回落（配置的实现不可用，实际走 simple） */
    private boolean fallback;
    /** 不可用原因码（{@link SearchUnavailableReason#name()}，可用时为 null） */
    private String reasonCode;
    /** 不可用原因文案（可直接展示） */
    private String reason;

    // ---------- 集群 ----------
    private String serverVersion;
    private String clusterName;
    /** green / yellow / red */
    private String status;
    private int nodeCount;
    private int indexCount;
    private long docCount;
    private long storeSizeBytes;
    /** 存储大小人类可读（1024 进制，B/KB/MB/GB/TB） */
    private String storeSizeHuman;

    // ---------- JVM 堆（取所有节点求和，百分比取最大） ----------
    private long jvmHeapUsedBytes;
    private long jvmHeapMaxBytes;
    private int jvmHeapUsedPercent;

    // ---------- 分片 ----------
    private int shardsActive;
    private int shardsActivePrimary;
    private int shardsRelocating;
    private int shardsInitializing;
    private int shardsUnassigned;

    // ---------- 明细 ----------
    private List<NodeInfo> nodes = new ArrayList<>();
    private List<IndexInfo> indices = new ArrayList<>();

    /** 采集时间（yyyy-MM-dd HH:mm:ss） */
    private String collectedAt;

    public SearchHealthSnapshot() {
    }

    /** 拷贝构造：monitor 侧 VO（{@code EsInfoVO}）继承本类后据此搬运，避免字段双写漂移 */
    public SearchHealthSnapshot(SearchHealthSnapshot src) {
        if (src == null) {
            return;
        }
        this.available = src.available;
        this.implementation = src.implementation;
        this.configuredType = src.configuredType;
        this.fallback = src.fallback;
        this.reasonCode = src.reasonCode;
        this.reason = src.reason;
        this.serverVersion = src.serverVersion;
        this.clusterName = src.clusterName;
        this.status = src.status;
        this.nodeCount = src.nodeCount;
        this.indexCount = src.indexCount;
        this.docCount = src.docCount;
        this.storeSizeBytes = src.storeSizeBytes;
        this.storeSizeHuman = src.storeSizeHuman;
        this.jvmHeapUsedBytes = src.jvmHeapUsedBytes;
        this.jvmHeapMaxBytes = src.jvmHeapMaxBytes;
        this.jvmHeapUsedPercent = src.jvmHeapUsedPercent;
        this.shardsActive = src.shardsActive;
        this.shardsActivePrimary = src.shardsActivePrimary;
        this.shardsRelocating = src.shardsRelocating;
        this.shardsInitializing = src.shardsInitializing;
        this.shardsUnassigned = src.shardsUnassigned;
        this.nodes = src.nodes;
        this.indices = src.indices;
        this.collectedAt = src.collectedAt;
    }

    /**
     * 构造「不可用」快照：<b>不抛异常</b>，把原因交给页面展示。
     */
    public static SearchHealthSnapshot unavailable(String implementation, String configuredType,
                                                   boolean fallback, SearchUnavailableReason reason,
                                                   String detail) {
        SearchHealthSnapshot snapshot = new SearchHealthSnapshot();
        snapshot.setAvailable(false);
        snapshot.setImplementation(implementation);
        snapshot.setConfiguredType(configuredType);
        snapshot.setFallback(fallback);
        snapshot.setReasonCode(reason == null ? null : reason.name());
        snapshot.setReason(reason == null ? detail : reason.text(detail));
        return snapshot;
    }

    /** 字节数转人类可读（1024 进制） */
    public static String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        return (idx == 0 ? (long) value + " " : String.format("%.2f ", value)) + units[idx];
    }

    /** 节点明细（{@code _cat/nodes}） */
    @Data
    public static class NodeInfo {
        private String name;
        private String ip;
        private String version;
        /**
         * 节点角色：ES 7.x 为 {@code di}/{@code mdi} 等短码，ES 8.x/9.x 为 {@code cdfhilmrstw} 长码。
         * <b>两个大版本字面值不同，前端只做原样展示不做解析</b>。
         */
        private String roles;
        /** 是否主节点（{@code _cat/nodes} 的 master 列为 * 表示是） */
        private boolean master;
        private int heapPercent;
        private int ramPercent;
        private int cpu;
        private double load1m;
    }

    /** 索引明细（{@code _cat/indices}） */
    @Data
    public static class IndexInfo {
        private String index;
        private String health;
        private String status;
        private long docsCount;
        private long storeSizeBytes;
        private String storeSizeHuman;
        private int pri;
        private int rep;
    }
}

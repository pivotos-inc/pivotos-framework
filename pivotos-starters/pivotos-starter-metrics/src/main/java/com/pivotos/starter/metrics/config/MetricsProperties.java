package com.pivotos.starter.metrics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指标基础设施配置（V3.1-F · P1-B8-1）。
 *
 * <h3>开关语义</h3>
 * <pre>
 *   pivotos.metrics.enabled=true           总开关（默认开）：关掉后 JVM/HTTP/连接池/业务埋点全停，
 *                                           且抓取端点不注册（404），业务侧注入的 BusinessMetrics 降级为 no-op
 *   pivotos.metrics.prometheus.enabled      抓取端点开关（默认开）：只关端点，指标仍在内存里
 *   pivotos.metrics.prometheus.path        抓取路径（默认 /actuator/prometheus，沿用 Prometheus 生态惯例，
 *                                           使 scrape_config 的 metrics_path 零改动）
 *   pivotos.metrics.slow-request.*         慢接口统计（默认开，阈值 1000ms）
 * </pre>
 *
 * <h3>为什么默认开</h3>
 * 指标是运维基础设施，与 {@code /health/live} 同口径：默认可用、但必须靠网络层（Nginx / 安全组）
 * 限制在内网。想整体关掉就显式配 {@code PIVOTOS_METRICS_ENABLED=false}。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
@Data
@ConfigurationProperties(prefix = "pivotos.metrics")
public class MetricsProperties {

    /** 总开关：false 时本模块零装配（业务埋点降级为 no-op，抓取端点不注册） */
    private boolean enabled = true;

    private Prometheus prometheus = new Prometheus();

    private SlowRequest slowRequest = new SlowRequest();

    @Data
    public static class Prometheus {

        /** 是否注册 Prometheus 抓取端点 */
        private boolean enabled = true;

        /**
         * 抓取端点路径。默认 {@code /actuator/prometheus} 是刻意与 Prometheus 生态保持一致
         * （{@code metrics_path} 默认就是这个值），即便本端点自研、非 actuator 提供。
         */
        private String path = "/actuator/prometheus";
    }

    @Data
    public static class SlowRequest {

        /** 是否统计慢接口（超阈值的请求计数 + 耗时 Timer） */
        private boolean enabled = true;

        /** 慢接口阈值（毫秒）：{@code >=} 该值才计一次 */
        private long thresholdMs = 1000L;
    }
}

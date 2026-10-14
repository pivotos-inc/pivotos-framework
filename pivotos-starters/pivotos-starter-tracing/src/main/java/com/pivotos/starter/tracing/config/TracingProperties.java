package com.pivotos.starter.tracing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 链路追踪配置（v3.2.0 · P1-B8-2）。
 *
 * <h3>开关语义</h3>
 * <pre>
 *   pivotos.tracing.enabled=false          总开关（默认关）：关掉后本模块零装配，
 *                                            OTLP 导出器、X-Trace-Id 传播器、MDC 补全都消失，
 *                                            既有 TraceIdFilter（starter-core）行为**一字不变**
 *   pivotos.tracing.otlp.enabled=true      OTLP 导出开关：只关导出，仍生成 span
 *   pivotos.tracing.otlp.endpoint          Collector 地址（默认 http://localhost:4318/v1/traces）
 *   pivotos.tracing.otlp.timeout           导出超时（默认 5s）
 *   pivotos.tracing.sampling.probability   采样率（默认 1.0 全采；生产建议 0.1）
 *   pivotos.tracing.propagation.enabled    X-Trace-Id 桥接开关（默认开）
 *   pivotos.tracing.mdc.enabled            MDC 补 userId / tenantId（默认开）
 * </pre>
 *
 * <h3>为什么默认关</h3>
 * 与指标（B8-1 默认开）**刻意不同**：tracing 默认关。理由有三：
 * <ol>
 *   <li>没有 Collector 时开启只会得到「导出失败」的日志噪音与无谓的 span 开销；</li>
 *   <li>默认形态要求「零行为变化」，而 tracing 会给每个请求加 span、改 MDC；</li>
 *   <li>要开就必须先把 Collector 地址配好 —— 这正是 {@code PIVOTOS_TRACING_ENABLED=true} +
 *       {@code PIVOTOS_TRACING_OTLP_ENDPOINT=...} 两个变量一起给的意思（只给前者没有意义）。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 3.2.0（v3.2.0 可观测性 · P1-B8-2）
 */
@Data
@ConfigurationProperties(prefix = "pivotos.tracing")
public class TracingProperties {

    /** 总开关：false 时本模块零装配（默认关，理由见类注释） */
    private boolean enabled = false;

    private Otlp otlp = new Otlp();

    private Propagation propagation = new Propagation();

    private Mdc mdc = new Mdc();

    /** 采样率：0.0 ~ 1.0，1.0 = 全采 */
    private double samplingProbability = 1.0d;

    @Data
    public static class Otlp {

        /** 是否向 OTLP Collector 导出 span */
        private boolean enabled = true;

        /**
         * Collector 的 traces 端点。默认沿用 OTel 生态惯例的 4318（HTTP）。
         * 注意与 4317（gRPC）区分：写错端口的表现是「导出超时」而不是「连接被拒」，极易误判。
         */
        private String endpoint = "http://localhost:4318/v1/traces";

        /** 导出超时（毫秒） */
        private long timeoutMs = 5000L;
    }

    @Data
    public static class Propagation {

        /**
         * 是否把既有的 {@code X-Trace-Id}（TraceIdFilter 生成/继承，32 hex）桥接成 OTel traceId。
         * 关掉则 OTel 自行生成 traceId，与网关/前端看到的 X-Trace-Id **对不上**。
         */
        private boolean enabled = true;

        /** 是否在响应头回写 OTel 当前 span 的 traceId（与 X-Trace-Id 同值，便于排障） */
        private boolean writeResponseHeader = true;
    }

    @Data
    public static class Mdc {

        /** 是否把 userId / username / tenantId 补进 MDC（traceId 由 starter-core 的 TraceIdFilter 负责） */
        private boolean enabled = true;
    }
}

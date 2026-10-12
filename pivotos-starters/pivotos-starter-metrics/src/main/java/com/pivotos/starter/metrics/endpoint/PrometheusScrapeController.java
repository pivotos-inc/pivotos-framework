package com.pivotos.starter.metrics.endpoint;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus 抓取端点（V3.1-F · P1-B8-1）：把 Micrometer 的指标以 Prometheus 文本格式吐出去。
 *
 * <h3>为什么自研而不引 actuator</h3>
 * Boot 4.1 已经把 actuator 拆开了：{@code PrometheusScrapeEndpoint}（端点类）归
 * {@code spring-boot-micrometer-metrics}，而把它暴露成 HTTP 的基础设施归
 * {@code spring-boot-actuator}。引后者会把 health / env / beans / heapdump / threaddump /
 * loggers 一整套端点自动配置拖进 classpath —— 我们只想要「吐指标」这一件事，
 * 却要额外承担一整套端点面的开关管理（配错 {@code exposure.include} 就是匿名面扩大）。
 * 这里 60 行自研即可，且与 V3.1-B 定的「探活端点自研、不引 actuator」同款口径。
 *
 * <h3>路径为什么仍是 /actuator/prometheus</h3>
 * 纯为 Prometheus 生态惯例：默认 {@code scrape_config.metrics_path} 就是它，运维侧零改动。
 * 本路径下<b>只有这一个自研端点</b>，其它 {@code /actuator/*} 仍然不存在
 * （会被 {@code GlobalExceptionHandler} 吃成 200 + {@code code=1004}，这是既有语义，本轮未动）。
 *
 * <h3>安全口径（与 /health/live 同款）</h3>
 * 端点<b>免鉴权</b>：Prometheus server 抓取不带登录态。因此它已被登记为
 * {@code P0ArchitectureTest} 的 {@code ANONYMOUS_ENDPOINTS} 与匿名扫描脚本的白名单
 * （登记即欠条：这是已知且接受的匿名面）。生产部署<b>必须</b>在网络层限制内网访问。
 * 不需要匿名抓取时，用 {@code PIVOTOS_METRICS_PROMETHEUS_ENABLED=false} 整个摘掉端点。
 *
 * <h3>输出</h3>
 * {@code text/plain; version=0.0.4; charset=utf-8}。没有指标时返回空串（不是 404），
 * 这是 Prometheus 的正常空抓取。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
@RestController
@RequestMapping("${pivotos.metrics.prometheus.path:/actuator/prometheus}")
@RequiredArgsConstructor
public class PrometheusScrapeController {

    private final PrometheusMeterRegistry registry;

    @GetMapping(produces = "text/plain; version=0.0.4; charset=utf-8")
    public String scrape() {
        return registry.scrape();
    }
}

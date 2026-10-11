package com.pivotos.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运维探活配置（{@code pivotos.ops.health.*}）。
 *
 * <h3>默认值即安全值</h3>
 * <ul>
 *   <li>{@code enabled=true}：探针默认开——关掉它，Nginx/k8s 就退回「只能按端口探活」的旧形态。</li>
 *   <li>{@code showDetailsAnonymously=false}：明细<b>默认只给已登录请求</b>。生产严禁开；
 *       开了等于把「用了什么连接池 / 跑在哪个 profile / 是否已回落」匿名公开。</li>
 *   <li>{@code timeoutMs=3000}：单依赖预算。探针会被高频轮询，宁可早失败也别拖住。</li>
 * </ul>
 *
 * <p>覆盖方式（Spring 松散绑定）：环境变量 {@code PIVOTOS_OPS_HEALTH_TIMEOUTMS} /
 * {@code PIVOTOS_OPS_HEALTH_SHOWDETAILSANONYMOUSLY} / {@code PIVOTOS_OPS_HEALTH_ENABLED}，
 * 或 yml 里写 {@code pivotos.ops.health.timeout-ms}。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@Data
@ConfigurationProperties(prefix = "pivotos.ops.health")
public class OpsHealthProperties {

    /** 总开关：false 时两个端点都不注册（请求落到全局异常处理，返回 200 + code=1004） */
    private boolean enabled = true;

    /** 单个依赖探活预算（毫秒），最小按 1s 换算给 JDBC */
    private int timeoutMs = 3000;

    /** 是否对匿名请求暴露明细（含实现名/回落原因/异常类名），生产严禁开 */
    private boolean showDetailsAnonymously = false;
}

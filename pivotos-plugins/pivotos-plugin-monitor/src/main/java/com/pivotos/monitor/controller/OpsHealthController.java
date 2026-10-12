package com.pivotos.monitor.controller;

import com.pivotos.monitor.config.OpsHealthProperties;
import com.pivotos.monitor.service.OpsHealthService;
import com.pivotos.monitor.service.health.HealthSnapshot;
import com.pivotos.monitor.support.HealthPayload;
import com.pivotos.starter.core.context.LoginContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维探活端点（v3.1.0 生产化 · A2）。
 *
 * <h3>为什么是自研端点而不是 actuator</h3>
 * 全仓零 actuator / 零 micrometer（见《v3.1.0 生产化评估》O1）。直接引 actuator 会把
 * micrometer-observation 一整套拖进 fat jar，还要处理它和本框架
 * {@code @RestControllerAdvice}（见 {@code GlobalExceptionHandler}）对端点异常的争抢；
 * 而这里只需要「进程活着吗 / 依赖就绪吗」两个结论，自研 100 行即可控可测、零新增三方依赖。
 *
 * <h3>状态码语义（本类是全系统里唯一「状态码即结论」的地方）</h3>
 * <pre>
 *   GET /health/live  →  200 {"status":"UP"}                   零依赖检查，进程能响应即存活
 *   GET /health/ready →  200 {"status":"UP"}
 *                       200 {"status":"DEGRADED", "checks":{...}}
 *                       503 {"status":"DOWN",    "checks":{...}}
 * </pre>
 * <b>其余端点仍然一律 HTTP 200</b>：全局异常处理把 NoResourceFoundException 转成了
 * {@code R.fail(1004)} + {@code ResponseEntity.ok()}，所以不存在的路径也返回 200——既有的
 * E2E/前端判据依赖这条语义，本轮<b>一个字都没改</b>。判其余端点请继续读 body 的 {@code code}。
 *
 * <h3>匿名可见面（安全口径）</h3>
 * 两个端点都免鉴权（Nginx/k8s 要能打），但匿名只能拿到 {@code status} + {@code checks} 布尔表；
 * 明细（实现名 / 配置值 / 是否回落 / 异常类名 / 运行时 profile 与 uptime）默认<b>仅已登录请求可见</b>，
 * 只有显式配 {@code pivotos.ops.health.show-details-anonymously=true} 才对匿名开放（生产严禁）。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@Tag(name = "运维探活", description = "进程存活与依赖就绪探活，匿名可读粗状态")
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pivotos.ops.health", name = "enabled", matchIfMissing = true)
public class OpsHealthController {

    private final OpsHealthService healthService;
    private final OpsHealthProperties properties;
    private final Environment environment;

    /**
     * 存活探活（liveness）：不做任何依赖检查、不碰网络与 IO——
     * <b>心跳被 DB 抖动拖死，比没有心跳更糟</b>（这是 Spring 官方把 liveness/readiness 分开的原因）。
     */
    @Operation(summary = "存活探活", description = "零依赖检查，进程能响应即 UP")
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(HealthPayload.live(detailsVisible(), environment));
    }

    /**
     * 就绪探活（readiness）：检查数据库（关键）、Redis（关键）、检索（非关键，回落 simple 只标降级）。
     */
    @Operation(summary = "就绪探活", description = "关键依赖不可用返回 HTTP 503，非关键依赖不可用返回 200 + DEGRADED")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        HealthSnapshot snapshot = healthService.probe();
        return ResponseEntity.status(snapshot.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(HealthPayload.ready(snapshot, detailsVisible(), environment));
    }

    /** 明细是否对外可见：默认要多走一层「已登录」 */
    private boolean detailsVisible() {
        return properties.isShowDetailsAnonymously() || LoginContext.isLogin();
    }
}

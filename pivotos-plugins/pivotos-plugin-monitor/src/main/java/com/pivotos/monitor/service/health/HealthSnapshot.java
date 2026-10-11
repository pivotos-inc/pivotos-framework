package com.pivotos.monitor.service.health;

import lombok.Data;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探活快照：粗状态（可对外）+ 明细（默认仅登录可见）。
 *
 * <h3>为什么拆两层</h3>
 * 探针要能被 Nginx / k8s 匿名调用，但绝不能因此把内网拓扑、实现细节一起公开——所以匿名只给
 * {@code status} + {@code checks}（布尔表），明细由控制器按登录态裁剪后再带出。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@Data
public class HealthSnapshot {

    private HealthStatus status = HealthStatus.UP;

    /** 检查项名 → 是否可用（匿名可见） */
    private Map<String, Boolean> checks = new LinkedHashMap<>();

    /** 明细（默认仅登录可见） */
    private List<HealthCheckResult> details = new ArrayList<>();

    /**
     * 汇总：关键项不可用 → DOWN；仅非关键项不可用 → DEGRADED；全可用 → UP。
     * <b>缺席（{@code absent}）的项不参与结论</b>——没引 Redis 的系统不该因 Redis 缺席被判降级。
     */
    public static HealthSnapshot aggregate(List<HealthCheckResult> results) {
        HealthSnapshot snapshot = new HealthSnapshot();
        boolean criticalDown = false;
        boolean anyDown = false;
        for (HealthCheckResult result : results) {
            if (result == null) {
                continue;
            }
            snapshot.getChecks().put(result.getName(), result.isUp());
            boolean counted = !result.isAbsent();
            if (counted && !result.isUp()) {
                anyDown = true;
                if (result.isCritical()) {
                    criticalDown = true;
                }
            }
        }
        snapshot.setDetails(results);
        snapshot.setStatus(criticalDown ? HealthStatus.DOWN : (anyDown ? HealthStatus.DEGRADED : HealthStatus.UP));
        return snapshot;
    }

    /** DOWN → 503，其余 200 */
    public HttpStatus httpStatus() {
        return status == HealthStatus.DOWN ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
    }
}

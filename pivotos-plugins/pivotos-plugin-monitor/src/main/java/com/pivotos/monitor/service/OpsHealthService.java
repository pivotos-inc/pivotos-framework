package com.pivotos.monitor.service;

import com.pivotos.monitor.config.OpsHealthProperties;
import com.pivotos.monitor.service.health.HealthCheckResult;
import com.pivotos.monitor.service.health.HealthCheckStep;
import com.pivotos.monitor.service.health.HealthSnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 依赖就绪探活汇总。
 *
 * <h3>汇总口径</h3>
 * 任一<b>关键</b>项不可用 → {@code DOWN}（HTTP 503）；仅非关键项不可用 → {@code DEGRADED}（200）；
 * 全部可用 → {@code UP}（200）。<b>缺席项（没装配的依赖）不参与结论</b>。
 *
 * <h3>为什么这里还要再包一层 try</h3>
 * {@link HealthCheckStep} 的契约是「自己兜住异常」，但契约守不住所有实现（新加检查项的人会忘）。
 * 探针是最不能 500 的地方——这里兜一层，把违规实现记成 DOWN 并留 WARN，而不是让 Nginx 拿到 500。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@RequiredArgsConstructor
public class OpsHealthService {

    private static final Logger log = LoggerFactory.getLogger(OpsHealthService.class);

    private final OpsHealthProperties properties;
    private final List<HealthCheckStep> steps;

    public HealthSnapshot probe() {
        int budget = Math.max(200, properties.getTimeoutMs());
        List<HealthCheckResult> results = new ArrayList<>();
        for (HealthCheckStep step : steps) {
            try {
                if (step.absent()) {
                    results.add(HealthCheckResult.absent(step.name(), step.critical(), "依赖未装配，跳过检查"));
                    continue;
                }
                HealthCheckResult result = step.probe(budget);
                results.add(result == null
                        ? HealthCheckResult.error(step.name(), step.critical(), "探活项返回了 null", null, 0L)
                        : result);
            } catch (Exception e) {
                log.warn("[PivotOS][ops] 探活项 {} 自身抛出异常（违反 HealthCheckStep 契约，已记为不可用）",
                        step.name(), e);
                results.add(HealthCheckResult.error(step.name(), step.critical(),
                        "探活项自身抛出异常", e, 0L));
            }
        }
        return HealthSnapshot.aggregate(results);
    }
}

package com.pivotos.monitor.support;

import com.pivotos.monitor.service.health.HealthSnapshot;
import com.pivotos.monitor.service.health.HealthStatus;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探活响应体构造（<b>刻意抽成纯函数</b>，好让「匿名能看见什么」这件事能被单测钉死）。
 *
 * <h3>匿名（Nginx / k8s probe）只能拿到</h3>
 * <pre>{"status":"UP","checks":{"db":true,"redis":true,"search":true}}</pre>
 * 多一个字段都算扩大匿名可见面，所以这里<b>不做「顺手加个版本方便排查」</b>那样的妥协。
 *
 * <h3>明细（默认仅登录可见）追加</h3>
 * {@code details}（实现名 / 配置值 / 是否回落 / 异常类名）与 {@code app}（profile / uptime）。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
public final class HealthPayload {

    private HealthPayload() {
    }

    public static Map<String, Object> live(boolean detailsVisible, Environment environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HealthStatus.UP.name());
        if (detailsVisible) {
            body.put("app", AppRuntimeInfo.snapshot(environment));
        }
        return body;
    }

    public static Map<String, Object> ready(HealthSnapshot snapshot, boolean detailsVisible, Environment environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot.getStatus().name());
        body.put("checks", snapshot.getChecks());
        if (detailsVisible) {
            body.put("details", snapshot.getDetails());
            body.put("app", AppRuntimeInfo.snapshot(environment));
        }
        return body;
    }
}

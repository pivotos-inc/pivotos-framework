package com.pivotos.monitor.support;

import org.springframework.core.env.Environment;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行态信息采集（探活明细用，<b>默认只给已登录请求</b>）。
 *
 * <h3>刻意不采集哪些</h3>
 * 不落 PID 文件路径、JVM 参数、命令行、系统环境变量、主机名与内网 IP——这些组合起来就是一份
 * 部署画像，匿名探针带上等于公开。只留下运维排障必需的「进程跑了多久 + 生效 profile + 应用名」。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
public final class AppRuntimeInfo {

    private AppRuntimeInfo() {
    }

    public static Map<String, Object> snapshot(Environment environment) {
        Map<String, Object> info = new LinkedHashMap<>();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        info.put("uptimeSeconds", Duration.ofMillis(uptimeMs).toSeconds());
        info.put("activeProfiles", profiles(environment));
        info.put("application", environment.getProperty("spring.application.name", "pivotos-admin-server"));
        return info;
    }

    private static List<String> profiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length > 0) {
            return Arrays.asList(active);
        }
        String[] defaults = environment.getDefaultProfiles();
        return defaults.length == 0 ? List.of("none") : Arrays.asList(defaults);
    }
}

package com.pivotos.starter.search.esjava;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 真机 IT 的「待测服务端」解析（供 {@code EsJavaRealServerIT} 与 {@code EsJavaScoredRealServerIT} 共用）。
 * <p><b>无环境变量时返回空列表</b>，配合 {@code @EnabledIf} 让整个类 Skipped——
 * 真机 IT 绝不能因为没有 ES 就让全量 IT 变红（S121/S127 已两次踩过）。
 *
 * <pre>
 * # 多目标（name|uri|username|password|compatibilityMode，分号分隔）
 * PIVOTOS_ES_TARGETS='es9|http://175.24.176.176:9200|elastic|pwd|false;es717|http://175.24.176.176:9201|elastic|pwd|true'
 *
 * # 单目标（兼容旧口径）
 * PIVOTOS_ES_URIS=http://host:9200 PIVOTOS_ES_USERNAME=elastic PIVOTOS_ES_PASSWORD=xxx
 * </pre>
 *
 * <p>注意 <b>7.17 目标必须 compatibilityMode=true</b>（compatible-with=7 头），
 * 而 8.x/9.x 必须 false（ES 9.5.3 实测拒绝该兼容头）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsItTargets {

    private EsItTargets() {
    }

    /** 一个待测服务端 */
    public record EsTarget(String name, List<String> uris, String username, String password,
                           boolean compatibilityMode) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static boolean configured() {
        return !targets().isEmpty();
    }

    public static List<EsTarget> targets() {
        String multi = System.getenv("PIVOTOS_ES_TARGETS");
        if (multi != null && !multi.isBlank()) {
            List<EsTarget> parsed = new ArrayList<>();
            for (String raw : multi.split(";")) {
                if (raw.isBlank()) {
                    continue;
                }
                String[] f = raw.trim().split("\\|");
                if (f.length < 2) {
                    throw new IllegalStateException(
                            "PIVOTOS_ES_TARGETS 片段格式应为 name|uri|username|password|compatibilityMode，实际：" + raw);
                }
                parsed.add(new EsTarget(
                        f[0].trim(),
                        Arrays.stream(f[1].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                        f.length > 2 ? f[2].trim() : "",
                        f.length > 3 ? f[3].trim() : "",
                        f.length > 4 && Boolean.parseBoolean(f[4].trim())));
            }
            return parsed;
        }
        String uris = System.getenv("PIVOTOS_ES_URIS");
        if (uris != null && !uris.isBlank()) {
            return List.of(new EsTarget("default",
                    Arrays.stream(uris.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                    System.getenv().getOrDefault("PIVOTOS_ES_USERNAME", ""),
                    System.getenv().getOrDefault("PIVOTOS_ES_PASSWORD", ""),
                    Boolean.parseBoolean(System.getenv().getOrDefault("PIVOTOS_ES_COMPATIBILITY_MODE", "false"))));
        }
        return List.of();
    }
}

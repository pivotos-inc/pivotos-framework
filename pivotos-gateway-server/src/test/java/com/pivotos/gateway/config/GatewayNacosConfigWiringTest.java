package com.pivotos.gateway.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关配置中心接线的静态守卫（V3-S3 ⑤）。
 *
 * <p>与 admin-server 的 {@code NacosConfigImportGuardTest} 同一口径，守的是两条会
 * 「静默失效」或「静默泄密」的红线：
 * <ol>
 *     <li>{@code spring.config.import} 的 dataId 必须带 {@code optional:} 前缀；</li>
 *     <li>Nacos 连接信息只能是 {@code ${...}} 占位符（yml 进 git，写明文凭据就是一次事故）。</li>
 * </ol>
 *
 * <p><b>为什么网关要单独写一份而不是复用</b>：两个是不同的 Maven 模块（网关是独立 jar），
 * 测试类不共用；而「网关这一份被漏掉」恰恰是本项要防的事——补 QM 侧接线最容易漏的就是第二个应用。
 *
 * <p>刻意不用 YAML 解析器：要断言的是「原文形态」，解析完反而看不到 {@code optional:}。
 */
class GatewayNacosConfigWiringTest {

    private static final Path YML = Path.of("src/main/resources/application.yml");

    /** 值必须是纯占位符（允许 ${NAME:default} 形态）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*}$");

    private static final Pattern NACOS_KEY =
        Pattern.compile("^\\s{8}(username|password|server-addr|namespace|group)\\s*:\\s*(\\S.*)$");

    @Test
    void 网关已接配置中心且每一项都带optional前缀() throws IOException {
        List<String> imports = collectImports();
        assertFalse(imports.isEmpty(),
            "网关 application.yml 里找不到 spring.config.import——配置中心接线被删了？");
        for (String item : imports) {
            assertTrue(item.startsWith("optional:"),
                "网关 spring.config.import 每项都必须以 optional: 开头，发现违规项：" + item
                    + "（去掉前缀 = Nacos 不可达时网关直接起不来）");
        }
    }

    @Test
    void 网关dataId不使用adminServer的那一份() throws IOException {
        for (String item : collectImports()) {
            assertFalse(item.contains("pivotos-admin-server"),
                "网关不应直接复用后端 dataId（会把网关路由变更与后端配置绑成一次发布）：" + item);
        }
    }

    @Test
    void nacos连接信息只能是环境变量占位符() throws IOException {
        List<String> lines = Files.readAllLines(YML, StandardCharsets.UTF_8);
        boolean inNacos = false;
        int checked = 0;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();
            if (!inNacos) {
                if (indent == 4 && trimmed.equals("nacos:")) {
                    inNacos = true;
                }
                continue;
            }
            if (indent <= 4) {
                inNacos = false;
                continue;
            }
            Matcher m = NACOS_KEY.matcher(line);
            if (m.matches()) {
                String value = m.group(2).trim();
                assertTrue(PLACEHOLDER.matcher(value).matches(),
                    "网关 Nacos 的 " + m.group(1) + " 必须是 ${...} 占位符，不得落明文。当前值：" + value);
                checked++;
            }
        }
        assertTrue(checked >= 5,
            "网关 nacos 段应有至少 5 个连接信息键，实际扫到 " + checked + " 个——块缩进变了？");
    }

    /** spring 的直接子项必须 2 空格缩进：写成 4 空格会落进别的子树，属性静默失效 */
    @Test
    void spring直接子项必须是两空格缩进() throws IOException {
        List<String> lines = Files.readAllLines(YML, StandardCharsets.UTF_8);
        int springIndent = -1;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.trim().equals("spring:")) {
                springIndent = indentOf(line);
                continue;
            }
            if (springIndent >= 0 && !line.isBlank()) {
                int indent = indentOf(line);
                if (indent <= springIndent) {
                    break;
                }
                if (indent == springIndent + 2) {
                    // config: / cloud: / …：网关侧只有这两个层级，逐个核对更稳
                    String key = line.trim();
                    assertTrue(key.equals("application:") || key.equals("config:") || key.equals("cloud:"),
                        "spring 的直接子项出现未知键：" + key + "（缩进写深会让属性落进别的子树）");
                }
            }
        }
        assertTrue(springIndent >= 0, "application.yml 里找不到 spring: 段");
    }

    private static List<String> collectImports() throws IOException {
        List<String> imports = new ArrayList<>();
        boolean inImport = false;
        for (String raw : Files.readAllLines(YML, StandardCharsets.UTF_8)) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.equals("import:") && indentOf(line) == 4) {
                inImport = true;
                continue;
            }
            if (inImport) {
                if (trimmed.startsWith("- ")) {
                    imports.add(trimmed.substring(2).trim());
                } else {
                    inImport = false;
                }
            }
        }
        return imports;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf('#');
        return idx < 0 ? line : line.substring(0, idx);
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}

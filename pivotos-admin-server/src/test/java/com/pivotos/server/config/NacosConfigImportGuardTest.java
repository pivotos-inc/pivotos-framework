package com.pivotos.server.config;

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
 * {@code application.yml} 配置中心接线的静态守卫（V3-S2 L6）。
 *
 * <p>守的是两条会「静默失效」或「静默泄密」的红线：
 * <ol>
 *     <li>{@code spring.config.import} 的 nacos dataId 必须带 {@code optional:} 前缀。
 *         少了它，「Nacos 不可达 / 鉴权失败 / dataId 不存在 / 默认形态无 nacos: 解析器」
 *         四态都会直接打断启动——而开发机上 Nacos 常常是可达的，改动后本地根本试不出来。</li>
 *     <li>Nacos 连接信息（username / password / server-addr / namespace / group）只能是
 *         {@code ${...}} 占位符。yml 会进 git，写明文凭据就是一次事故。</li>
 * </ol>
 *
 * <p>刻意不用 YAML 解析器：这里要断言的是「原文形态」（前缀、占位符），
 * 解析完反而看不到 {@code optional:} 这样的字面约束。
 */
class NacosConfigImportGuardTest {

    private static final Path YML = Path.of("src/main/resources/application.yml");

    /** 值必须是纯占位符（允许 ${NAME:default} 形态）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*}$");

    private static final Pattern NACOS_KEY =
        Pattern.compile("^\\s{8}(username|password|server-addr|namespace|group)\\s*:\\s*(\\S.*)$");

    @Test
    void 配置中心导入必须带optional前缀() throws IOException {
        List<String> imports = collectImports();
        assertFalse(imports.isEmpty(),
            "application.yml 里找不到 spring.config.import 的导入项，配置中心接线被删了？");
        for (String item : imports) {
            assertTrue(item.startsWith("optional:"),
                "spring.config.import 的每一项都必须以 optional: 开头，发现违规项：" + item
                    + "（去掉前缀 = Nacos 不可达时直接打断启动）");
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
                    "Nacos 的 " + m.group(1) + " 必须是 ${...} 环境变量占位符，yml 进 git 不得落明文。当前值：" + value);
                checked++;
            }
        }
        assertTrue(checked >= 5,
            "nacos 段里应有至少 5 个连接信息键（config/discovery 各若干），实际只扫到 " + checked + " 个——块缩进变了？");
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

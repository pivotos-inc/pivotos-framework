package com.pivotos.ai.coding.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 产物红线自动检查（S42 落地 D4 决策：正则 + 轻量文本扫描，落盘前拦截）。
 * <p>
 * 覆盖《10-S24-AI-Coding方案设计说明》§6.3 清单的文本可判部分：
 * 禁 ThreadLocal / 裸线程 / fastjson 1.x / 预览特性 / 自增 ID / 跨 Plugin 直接依赖 /
 * 路径穿越。ArchUnit 仍是落盘编译后的第二道防线。
 * <p>
 * 规则按文件类型定界（Java 规则只扫 .java、SQL 规则只扫 .sql），
 * 避免文档类产物（README/模板说明）命中规则条文本身造成误报（S42 实测踩坑）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class ArtifactLinter {

    private record Rule(String id, String label, Pattern pattern,
                        Predicate<String> pathScope, boolean checkPath) {
        boolean appliesTo(String path) {
            return pathScope.test(path);
        }

        boolean match(String text) {
            return pattern.matcher(text).find();
        }
    }

    private static final Predicate<String> JAVA = p -> p.endsWith(".java");
    private static final Predicate<String> SQL = p -> p.toLowerCase().endsWith(".sql");
    private static final Predicate<String> ANY = p -> true;

    private static final List<Rule> RULES = List.of(
            new Rule("R1", "禁 ThreadLocal（上下文一律 ScopedValue）",
                    Pattern.compile("java\\.lang\\.ThreadLocal|ThreadLocal<"), JAVA, false),
            new Rule("R2", "禁裸线程 new Thread / Executors 直用（须走 ContextExecutor）",
                    Pattern.compile("new\\s+Thread\\s*\\(|Executors\\.\\w+\\s*\\("), JAVA, false),
            new Rule("R3", "禁 fastjson 1.x（一律 fastjson2）",
                    Pattern.compile("com\\.alibaba\\.fastjson\\.(?!2)"), JAVA, false),
            new Rule("R4", "禁 JDK 预览特性",
                    Pattern.compile("--enable-preview"), ANY, false),
            new Rule("R5", "禁自增 ID（主键一律雪花）",
                    Pattern.compile("AUTO_INCREMENT", Pattern.CASE_INSENSITIVE), SQL, false),
            new Rule("R6", "禁跨 Plugin 直接依赖实现包（只许 -api 契约）",
                    Pattern.compile("import\\s+com\\.pivotos\\.(system|message|file|generator)\\.(?!api\\.)"), JAVA, false),
            new Rule("R7", "禁路径穿越 ../",
                    Pattern.compile("(^|/)\\.\\.(/|$)"), ANY, true)
    );

    /**
     * 扫描产物（路径 + 内容），返回违规报告条目；空列表 = 全绿。
     *
     * @param files filePath -> content
     * @return 违规模块描述列表，如 "R2 pivotos-plugins/x/Y.java: 禁裸线程…"
     */
    public List<String> lint(Map<String, String> files) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue() == null ? "" : entry.getValue();
            for (Rule rule : RULES) {
                if (!rule.appliesTo(path)) {
                    continue;
                }
                boolean hit = rule.checkPath() ? rule.match(path) : rule.match(content);
                if (hit) {
                    violations.add(rule.id() + " " + path + ": " + rule.label());
                }
            }
        }
        return violations;
    }
}

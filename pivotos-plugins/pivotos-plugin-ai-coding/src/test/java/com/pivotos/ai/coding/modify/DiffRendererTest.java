package com.pivotos.ai.coding.modify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性 diff 渲染器测试（A4-2 / S111 的核心闸门）。
 *
 * <p>三条红线在此锁死：
 * <ul>
 *   <li>hunk 头计数精确（spike K1 ① 号死法：LLM 直出 diff 的 hunk 头行数漂移）；</li>
 *   <li>上下文行逐字复制原文（② 号死法）；</li>
 *   <li>产物可被 {@code git apply} 直接消费（端到端可应用性，非仅格式正确）。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("DiffRenderer 测试")
class DiffRendererTest {

    private static final String ORIGINAL = String.join("\n",
            "package com.pivotos.demo;",
            "",
            "public class Demo {",
            "",
            "    public void run() {",
            "        System.out.println(\"before\");",
            "    }",
            "",
            "    public void tail() {",
            "        System.out.println(\"tail\");",
            "    }",
            "}",
            "");

    @Test
    @DisplayName("单行替换：hunk 头计数精确，上下文 3 行逐字复制")
    void singleLineReplacement() {
        String modified = ORIGINAL.replace("System.out.println(\"before\");",
                "System.out.println(\"before\");\n        System.out.println(\"after\");");
        String diff = DiffRenderer.render("a/b/Demo.java", ORIGINAL, modified);

        assertTrue(diff.startsWith("--- a/a/b/Demo.java\n+++ b/a/b/Demo.java\n"));
        // 插入点在第 6 行之后（前 6 行为 EQUAL），上下文 3 → 覆盖第 4~9 行（6 行），改后 7 行
        assertTrue(diff.contains("@@ -4,6 +4,7 @@"), "hunk 头计数须精确：\n" + diff);
        assertTrue(diff.contains("     public void run() {"), "上下文须逐字复制（含缩进）");
        assertTrue(diff.contains("         System.out.println(\"before\");"), "原文行作上下文保留");
        assertTrue(diff.contains("+        System.out.println(\"after\");"));
        assertTrue(deletedLines(diff).isEmpty(), "纯插入不应产生删除行：\n" + diff);
    }

    @Test
    @DisplayName("产物可被 git apply 消费（真 git 校验，无 git 时跳过）")
    void diffIsApplicableByGit(@TempDir Path dir) throws IOException, InterruptedException {
        String relative = "src/main/java/Demo.java";
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ORIGINAL, StandardCharsets.UTF_8);
        if (!gitAvailable(dir)) {
            // 无 git CLI 时退化为结构断言，不让 IT 硬依赖外部命令
            assertTrue(DiffRenderer.render(relative, ORIGINAL, ORIGINAL.replace("before", "after"))
                    .contains("@@ -"));
            return;
        }
        String modified = ORIGINAL.replace("before", "after");
        String diff = DiffRenderer.render(relative, ORIGINAL, modified);

        ProcessBuilder check = new ProcessBuilder("git", "apply", "--check", "-");
        check.directory(dir.toFile());
        check.redirectErrorStream(true);
        Process process = check.start();
        process.getOutputStream().write(diff.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, "确定性渲染的 diff 必须裸跑 git apply --check 即通过（不需 --recount）：" + out);
    }

    @Test
    @DisplayName("文件尾追加：hunk 落在末尾，不臆造行号")
    void appendAtTail() {
        String modified = ORIGINAL + "    public void extra() {\n    }\n";
        String diff = DiffRenderer.render("x/Demo.java", ORIGINAL, modified);
        assertTrue(diff.contains("+    public void extra() {"));
        assertTrue(diff.contains("+    }"));
        // 尾追加不应改动既有行（文件头 `---` 不算删除行，须按行判定）
        assertTrue(deletedLines(diff).isEmpty(), "追加形态不应产生删除行：" + diff);
    }

    @Test
    @DisplayName("删除行与无换行结尾标记")
    void deletionAndNoNewlineMarker() {
        String noTrailing = "line1\nline2";
        String modified = "line1";
        String diff = DiffRenderer.render("x/y.txt", noTrailing, modified);
        assertTrue(diff.contains("-line2"), "应含删除行");
        assertTrue(diff.contains("\\ No newline at end of file"), "原文无结尾换行须打标记");
    }

    @Test
    @DisplayName("远距离两处改动拆成两个 hunk；相邻改动合并为一个；相同输入返回空串")
    void hunkGroupingAndNoop() {
        // 首行与倒数第二行：相距远超 2×上下文+1，应拆两个 hunk
        String far = ORIGINAL.replace("package com.pivotos.demo;", "package com.pivotos.renamed;")
                .replace("System.out.println(\"tail\");", "System.out.println(\"TAIL\");");
        String farDiff = DiffRenderer.render("x/Demo.java", ORIGINAL, far);
        assertEquals(2, countOccurrences(farDiff, "@@ -"), "远距离改动应拆成两个 hunk：\n" + farDiff);

        // run/tail 两处相距 3 行（≤ 2×3+1），应合并成一个 hunk
        String near = ORIGINAL.replace("before", "AFTER").replace("tail", "TAIL");
        String nearDiff = DiffRenderer.render("x/Demo.java", ORIGINAL, near);
        assertEquals(1, countOccurrences(nearDiff, "@@ -"), "相邻改动应合并为一个 hunk：\n" + nearDiff);

        assertEquals("", DiffRenderer.render("x/Demo.java", ORIGINAL, ORIGINAL), "无改动应返回空串");
    }

    @Test
    @DisplayName("空文件与全新增：不抛异常且结构合法")
    void emptyAndFullInsert() {
        String diff = DiffRenderer.render("x/New.java", "", "public class New {\n}\n");
        assertTrue(diff.startsWith("--- a/x/New.java"));
        assertTrue(diff.contains("@@ -0,0 +1,2 @@"), "空文件新增的 hunk 头应为 -0,0 +1,2：\n" + diff);
    }

    @Test
    @DisplayName("行切分：结尾换行不产生空行")
    void splitLines() {
        assertEquals(List.of("a", "b"), DiffRenderer.splitLines("a\nb\n"));
        assertEquals(List.of("a", "b"), DiffRenderer.splitLines("a\nb"));
        assertTrue(DiffRenderer.splitLines("").isEmpty());
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + token.length();
        }
    }

    /** 取 diff 中真正的删除行（排除文件头 `---` 与无换行标记） */
    private static java.util.List<String> deletedLines(String diff) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("-") && !line.startsWith("--- ")) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean gitAvailable(Path dir) {
        try {
            ProcessBuilder init = new ProcessBuilder("git", "init", "-q");
            init.directory(dir.toFile());
            init.redirectErrorStream(true);
            return init.start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}

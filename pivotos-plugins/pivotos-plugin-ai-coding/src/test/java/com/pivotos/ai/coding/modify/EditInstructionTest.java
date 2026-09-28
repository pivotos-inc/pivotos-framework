package com.pivotos.ai.coding.modify;

import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_AMBIGUOUS;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化 edit 指令与存在性闸门测试（A4-2 / S111 的确定性核心）。
 *
 * <p>闸门的意义：LLM 只能描述「把哪段原文换成什么」，search 段逐字不存在/
 * 不唯一就整体拒绝——这是 spike K1「上下文非逐字复制」与 K3「文件外符号幻觉」
 * 共同的根治点，宁可拒绝也不猜。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("EditInstruction 测试")
class EditInstructionTest {

    private static final String SOURCE = String.join("\n",
            "public class Demo {",
            "    public void a() { log(); }",
            "    public void b() { log(); }",
            "}",
            "");

    @Test
    @DisplayName("唯一命中：正常替换")
    void replaceUnique() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("public void a() { log(); }",
                        "public void a() { log(); check(); }", 0, false, "加校验")));
        String result = instruction.apply(SOURCE);
        assertTrue(result.contains("public void a() { log(); check(); }"));
        assertTrue(result.contains("public void b() { log(); }"), "不应波及其它方法");
    }

    @Test
    @DisplayName("多处命中且未指定 occurrence → 7020 歧义拒绝（不猜）")
    void ambiguousRejected() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("log();", "safeLog();", 0, false, "换日志")));
        ServiceException e = assertThrows(ServiceException.class, () -> instruction.apply(SOURCE));
        assertEquals(CODING_EDIT_SEARCH_AMBIGUOUS.getCode(), e.getCode());
    }

    @Test
    @DisplayName("多处命中但指定 occurrence → 只改第 N 处")
    void occurrenceSelectsTarget() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("log();", "safeLog();", 2, false, "只改 b 方法")));
        String result = instruction.apply(SOURCE);
        assertTrue(result.contains("public void a() { log(); }"), "第 1 处不动");
        assertTrue(result.contains("public void b() { safeLog(); }"), "第 2 处被替换");
    }

    @Test
    @DisplayName("search 不存在 → 7019 拒绝（文件外/臆造片段拦截点）")
    void missingSearchRejected() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("public void c() { }", "x", 0, false, "臆造方法")));
        ServiceException e = assertThrows(ServiceException.class, () -> instruction.apply(SOURCE));
        assertEquals(CODING_EDIT_SEARCH_MISSING.getCode(), e.getCode());
    }

    @Test
    @DisplayName("occurrence 超出实际命中数 → 7019")
    void occurrenceOutOfRange() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("log();", "x", 9, false, "越界")));
        ServiceException e = assertThrows(ServiceException.class, () -> instruction.apply(SOURCE));
        assertEquals(CODING_EDIT_SEARCH_MISSING.getCode(), e.getCode());
    }

    @Test
    @DisplayName("append 块：追加到末尾且自动补换行（解决 S110 I6 遗留）")
    void appendBlock() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("", "public void c() { }", 0, true, "新增方法")));
        String result = instruction.apply("public class Demo {\n}");
        assertTrue(result.endsWith("public void c() { }\n"), "追加须保证结尾换行：" + result);
        assertTrue(result.startsWith("public class Demo {\n}"));
    }

    @Test
    @DisplayName("删除形态：replace 为空串")
    void deleteBlock() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("    public void b() { log(); }\n", "", 0, false, "删方法")));
        String result = instruction.apply(SOURCE);
        assertFalse(result.contains("public void b()"));
        assertTrue(result.contains("public void a()"));
    }

    @Test
    @DisplayName("多块顺序执行：后块可引用前块产物")
    void chainedBlocks() {
        EditInstruction instruction = new EditInstruction("x/Demo.java", List.of(
                new EditInstruction.Block("public class Demo {", "public class Demo {\n    // header", 0, false, "加注释"),
                new EditInstruction.Block("// header", "// header ok", 0, false, "改注释")));
        String result = instruction.apply(SOURCE);
        assertTrue(result.contains("// header ok"));
    }

    @Test
    @DisplayName("计数与定位工具方法")
    void countingHelpers() {
        assertEquals(2, EditInstruction.countOccurrences(SOURCE, "log();"));
        assertEquals(0, EditInstruction.countOccurrences(SOURCE, "nope"));
        assertEquals(0, EditInstruction.countOccurrences(SOURCE, ""));
        assertTrue(EditInstruction.indexOfOccurrence(SOURCE, "log();", 2)
                > EditInstruction.indexOfOccurrence(SOURCE, "log();", 1));
        assertEquals(-1, EditInstruction.indexOfOccurrence(SOURCE, "nope", 1));
    }
}

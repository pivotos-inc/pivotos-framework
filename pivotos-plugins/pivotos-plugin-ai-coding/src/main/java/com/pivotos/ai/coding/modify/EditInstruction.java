package com.pivotos.ai.coding.modify;

import com.pivotos.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_AMBIGUOUS;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING;

/**
 * 结构化 edit 指令（A4-2 / S111，形态取自 16 号清单决策项：search/replace 块）。
 *
 * <p>为什么不是行区间替换（15 号文档 spike 实证）：LLM 误读行号时行区间没有任何校验力，
 * 而 search 段的**逐字存在性校验**是一道确定性闸门——这是 spike K1「上下文非逐字复制」
 * 与 K3「文件外符号幻觉」共同的根治点：LLM 只能描述「把哪段原文换成什么」，
 * 换出来什么由确定性渲染器产出，LLM 全程不碰 diff 格式。
 *
 * <p>三种块形态：
 * <ol>
 *   <li>替换：{@code search} 非空，按 occurrence 定位唯一命中处替换为 replace；</li>
 *   <li>追加：{@code append=true}，replace 追加到文件末尾（解决 S110 遗留：
 *       「文件尾追加」没有行区间语义，定位给 34-38 行而文件仅 33 行）；</li>
 *   <li>删除：{@code search} 非空且 replace 为空串。</li>
 * </ol>
 *
 * <p><b>必须是 record（S112 修复）</b>：本对象会经 {@code objectMapper.writeValueAsString}
 * 入库 {@code edit_json} 并在 apply 时反序列化重放。曾用「普通类 + record 风格访问器
 * （{@code path()}/{@code blocks()}）」的写法——Jackson 3 按 getter 序列化普通类，
 * 无 getter 即序列化成 {@code {}}：入库空对象、apply 必报 7018、评审面 edit 恒空
 * （S111 遗留断点，E2E 当时只跑 prepare 不落盘故未暴露）。record 的组件访问器
 * 天然是 Jackson 属性，序列化输出 {@code {"path":…,"blocks":[…]}}，
 * 与 {@link EditInstructionParser} 的 {@code blocks} 键名兼容，读写闭环。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2；S112 修复序列化断点）
 */
public record EditInstruction(String path, List<Block> blocks) {

    public EditInstruction {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }


    /**
     * 在原文上应用全部 edit 块，返回改后文本。
     *
     * <p>两阶段：先在**原文**上逐块预校验（任一 search 不唯一/不存在即整体拒绝，7019/7020），
     * 再按块顺序在累积文本上执行替换。预校验保证「LLM 描述的是真实存在的原文」，
     * 顺序执行保证后块可以引用前块产物（例如先插 import 再改方法体）。
     *
     * @param original 目标文件原文
     * @return 改后文本
     */
    public String apply(String original) {
        if (original == null) {
            throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
        }
        // 顺序执行：每块在其执行时刻的当前文本上过闸门
        // （不预校验到原文：后块可以引用前块产物，例如先插 import 再改方法体；
        //   臆造片段在任何时刻的文本里都找不到，同样被 7019/7020 拦住）
        String current = original;
        for (Block block : blocks) {
            current = block.applyTo(current);
        }
        // 追加块统一在末尾执行（保证「追加」语义不因块顺序被插到中间）
        StringBuilder sb = new StringBuilder(current);
        for (Block block : blocks) {
            if (block.append() && block.replace() != null && !block.replace().isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                sb.append(block.replace());
                if (sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 计算 search 在文本中的出现次数（重叠不计，逐字匹配）。
     */
    public static int countOccurrences(String text, String search) {
        if (text == null || search == null || search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(search, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + search.length();
        }
    }

    /**
     * 按 occurrence（1-based）定位第 n 次出现的起始下标；occurrence&lt;=0 表示唯一命中。
     *
     * @return 命中下标，未命中返回 -1
     */
    public static int indexOfOccurrence(String text, String search, int occurrence) {
        if (text == null || search == null || search.isEmpty()) {
            return -1;
        }
        int seen = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(search, from);
            if (idx < 0) {
                return -1;
            }
            seen++;
            if (occurrence <= 0 || seen == occurrence) {
                return idx;
            }
            from = idx + search.length();
        }
    }

    /** 解析后的块落地校验：至少要有内容，且替换与追加互斥 */
    public static List<Block> sanitize(List<Block> raw) {
        List<Block> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Block block : raw) {
            if (block == null) {
                continue;
            }
            if (block.append()) {
                out.add(block);
            } else if (block.search() != null && !block.search().isEmpty()) {
                out.add(block);
            }
        }
        return out;
    }

    /**
     * 单个 edit 块。
     *
     * @param search     待替换的原文片段（逐字，含缩进）
     * @param replace    替换后的内容（空串表示删除）
     * @param occurrence 第几次命中（1-based）；&lt;=0 表示要求唯一命中
     * @param append     true 表示追加到文件末尾（不校验 search）
     * @param reason     LLM 给出的改动理由（评审面辅助材料，不作裁决）
     */
    public record Block(String search, String replace, int occurrence, boolean append, String reason) {

        public Block {
            if (replace == null) {
                replace = "";
            }
        }

        public String applyTo(String text) {
            if (append) {
                return text;
            }
            if (search == null || search.isEmpty()) {
                throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
            }
            int count = countOccurrences(text, search);
            if (count == 0) {
                throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
            }
            if (count > 1 && occurrence <= 0) {
                // 多处命中且未指定 occurrence —— 宁可拒绝也不猜
                throw new ServiceException(CODING_EDIT_SEARCH_AMBIGUOUS);
            }
            if (occurrence > count) {
                throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
            }
            int idx = indexOfOccurrence(text, search, occurrence);
            return text.substring(0, idx) + replace + text.substring(idx + search.length());
        }
    }
}

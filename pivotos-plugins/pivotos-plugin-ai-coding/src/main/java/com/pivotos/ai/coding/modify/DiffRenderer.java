package com.pivotos.ai.coding.modify;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性 unified diff 渲染器（A4-2 / S111）。
 *
 * <p>diff **只能由本渲染器产出，LLM 全程不碰 diff 格式**——这是 15 号文档实测结论：
 * LLM 直出 unified diff 有两种死法（hunk 头行数漂移 / 上下文行非逐字复制），
 * 前者 {@code git apply --recount} 可兜底，后者无药可救（spike I5/I6/I7 实证）。
 * 由结构化 edit 指令确定性渲染，两种死法同时归零：行数是算出来的、上下文是原文复制的。
 *
 * <p>实现：行级 LCS（先剥离公共前后缀缩小 DP 规模，再回溯生成 op 序列），
 * 按 3 行上下文聚合成 hunk，hunk 头计数精确。规模超阈值时退化为「全删+全插」单 hunk
 * （保证大文件不炸内存，产物仍可被 {@code git apply} 消费）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
public final class DiffRenderer {

    /** 上下文行数（git 默认 3） */
    private static final int CONTEXT = 3;

    /** DP 单元数上限，超出则退化（2000×2000 量级） */
    private static final long MAX_DP_CELLS = 4_000_000L;

    private static final String NO_NEWLINE_MARKER = "\\ No newline at end of file";

    private DiffRenderer() {
    }

    /**
     * 渲染 unified diff。
     *
     * @param relativePath 仓库相对路径（写入 a/ b/ 头，与 git apply 语义一致）
     * @param original     原文
     * @param modified     改后文本
     * @return unified diff；两文本完全相同时返回空串（调用方应判为无改动）
     */
    public static String render(String relativePath, String original, String modified) {
        if (original == null) {
            original = "";
        }
        if (modified == null) {
            modified = "";
        }
        if (original.equals(modified)) {
            return "";
        }
        boolean originNoNewline = !original.isEmpty() && !original.endsWith("\n");
        boolean modifiedNoNewline = !modified.isEmpty() && !modified.endsWith("\n");

        List<String> a = splitLines(original);
        List<String> b = splitLines(modified);

        List<Op> ops = diffOps(a, b);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(relativePath).append('\n');
        sb.append("+++ b/").append(relativePath).append('\n');
        appendHunks(sb, ops, a, b, originNoNewline, modifiedNoNewline);
        return sb.toString();
    }

    // ---------------- 行切分 ----------------

    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        String[] parts = text.split("\n", -1);
        int count = text.endsWith("\n") ? parts.length - 1 : parts.length;
        for (int i = 0; i < count; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    // ---------------- LCS ----------------

    private enum Kind {EQUAL, DELETE, INSERT}

    private record Op(Kind kind, int aIndex, int bIndex) {
    }

    static List<Op> diffOps(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        // 公共前缀
        int prefix = 0;
        while (prefix < n && prefix < m && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        // 公共后缀
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && a.get(n - 1 - suffix).equals(b.get(m - 1 - suffix))) {
            suffix++;
        }

        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(Kind.EQUAL, i, i));
        }

        int midN = n - prefix - suffix;
        int midM = m - prefix - suffix;
        List<Op> mid = (midN == 0 && midM == 0) ? List.of()
                : ((long) (midN + 1) * (midM + 1) > MAX_DP_CELLS
                ? fallbackOps(midN, midM) : lcsOps(a, b, prefix, suffix, midN, midM));
        ops.addAll(mid);

        for (int i = 0; i < suffix; i++) {
            ops.add(new Op(Kind.EQUAL, n - suffix + i, m - suffix + i));
        }
        return ops;
    }

    /** 规模超限：全删 + 全插（产物仍是合法 unified diff） */
    private static List<Op> fallbackOps(int midN, int midM) {
        List<Op> ops = new ArrayList<>(midN + midM);
        for (int i = 0; i < midN; i++) {
            ops.add(new Op(Kind.DELETE, i, -1));
        }
        for (int j = 0; j < midM; j++) {
            ops.add(new Op(Kind.INSERT, -1, j));
        }
        return ops;
    }

    private static List<Op> lcsOps(List<String> a, List<String> b, int prefix, int suffix, int midN, int midM) {
        int[][] dp = new int[midN + 1][midM + 1];
        for (int i = midN - 1; i >= 0; i--) {
            for (int j = midM - 1; j >= 0; j--) {
                dp[i][j] = a.get(prefix + i).equals(b.get(prefix + j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < midN && j < midM) {
            if (a.get(prefix + i).equals(b.get(prefix + j))) {
                ops.add(new Op(Kind.EQUAL, prefix + i, prefix + j));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, prefix + i, -1));
                i++;
            } else {
                ops.add(new Op(Kind.INSERT, -1, prefix + j));
                j++;
            }
        }
        while (i < midN) {
            ops.add(new Op(Kind.DELETE, prefix + i, -1));
            i++;
        }
        while (j < midM) {
            ops.add(new Op(Kind.INSERT, -1, prefix + j));
            j++;
        }
        return ops;
    }

    // ---------------- hunk 聚合 ----------------

    private static void appendHunks(StringBuilder sb, List<Op> ops, List<String> a, List<String> b,
                                    boolean originNoNewline, boolean modifiedNoNewline) {
        int size = ops.size();
        List<int[]> ranges = new ArrayList<>();
        int groupStart = -1;
        int groupEnd = -1;
        for (int i = 0; i < size; i++) {
            if (ops.get(i).kind() == Kind.EQUAL) {
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = Math.min(size, i + CONTEXT + 1);
            if (groupStart < 0) {
                groupStart = start;
                groupEnd = end;
            } else if (start <= groupEnd) {
                groupEnd = end;
            } else {
                ranges.add(new int[]{groupStart, groupEnd});
                groupStart = start;
                groupEnd = end;
            }
        }
        if (groupStart >= 0) {
            ranges.add(new int[]{groupStart, groupEnd});
        }

        int lastA = a.size() - 1;
        int lastB = b.size() - 1;
        for (int[] range : ranges) {
            int aStart = -1;
            int bStart = -1;
            int aCount = 0;
            int bCount = 0;
            for (int i = range[0]; i < range[1]; i++) {
                Op op = ops.get(i);
                if (op.kind() != Kind.INSERT) {
                    if (aStart < 0) {
                        aStart = op.aIndex();
                    }
                    aCount++;
                }
                if (op.kind() != Kind.DELETE) {
                    if (bStart < 0) {
                        bStart = op.bIndex();
                    }
                    bCount++;
                }
            }
            sb.append("@@ -").append(aStart + 1).append(',').append(aCount)
                    .append(" +").append(bStart + 1).append(',').append(bCount).append(" @@\n");
            for (int i = range[0]; i < range[1]; i++) {
                Op op = ops.get(i);
                switch (op.kind()) {
                    case EQUAL -> sb.append(' ').append(a.get(op.aIndex())).append('\n');
                    case DELETE -> {
                        sb.append('-').append(a.get(op.aIndex())).append('\n');
                        if (originNoNewline && op.aIndex() == lastA) {
                            sb.append(NO_NEWLINE_MARKER).append('\n');
                        }
                    }
                    case INSERT -> {
                        sb.append('+').append(b.get(op.bIndex())).append('\n');
                        if (modifiedNoNewline && op.bIndex() == lastB) {
                            sb.append(NO_NEWLINE_MARKER).append('\n');
                        }
                    }
                }
            }
        }
    }
}

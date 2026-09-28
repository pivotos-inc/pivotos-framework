package com.pivotos.ai.coding.locate;

import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性关键词召回（粗筛的召回下限保障）。
 *
 * <p>为什么要这一层：S107 spike 实测粗定位 top3 只有 7/8、top1 仅 2/8，唯一 miss（I4）是
 * 「会话」关键词被 AI 聊天域截获——纯语义召回在大仓库里会被高频同名域抢走。索引既然已经带
 * 符号表，就可以用符号/路径/类型的字面命中做一路确定性召回，与 LLM 粗筛取并集，
 * 保证「答案文件至少进得了候选集」；最终落点仍由精定位 + 仲裁决定，召回不代替决策。
 *
 * <p>打分：符号名命中 4 分（最强，方法是改动的直接落点）> 类型名 3 分 > 模块 1 分 > 路径 1 分。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public final class KeywordRecall {

    /** 非标识符字符（分词用） */
    private static final Pattern SPLIT = Pattern.compile("[^A-Za-z0-9_]+");

    /** 骆驼峰切分：deleteTemplate → delete / Template */
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private static final int SCORE_SYMBOL = 4;
    private static final int SCORE_TYPE = 3;
    private static final int SCORE_MODULE = 1;
    private static final int SCORE_PATH = 1;

    private static final int MIN_TOKEN = 3;
    private static final int MIN_CAMEL_TOKEN = 4;

    private KeywordRecall() {
    }

    /**
     * 关键词召回排序。
     *
     * @param snapshot 索引快照
     * @param texts    查询文本（意图 + LLM 解析出的关键词）
     * @param topK     返回条数
     * @return 命中的相对路径（按分值降序，分值相同按路径升序）
     */
    public static List<String> rank(CodeIndexSnapshot snapshot, List<String> texts, int topK) {
        if (snapshot == null || snapshot.entries().isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> tokens = tokenize(texts);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>(snapshot.entries().size());
        for (CodeFileEntry entry : snapshot.entries()) {
            int score = scoreEntry(entry, tokens);
            if (score > 0) {
                scored.add(new Scored(entry.relativePath(), score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(Scored::path));
        return scored.stream().limit(topK).map(Scored::path).toList();
    }

    /** 单条目打分（同一 token 只取最高命中档，避免同义词重复计分） */
    static int scoreEntry(CodeFileEntry entry, List<String> tokens) {
        String path = lower(entry.relativePath());
        String module = lower(entry.moduleName());
        String type = lower(entry.typeName());
        List<String> symbols = entry.symbols().stream().map(KeywordRecall::lower).toList();
        int total = 0;
        for (String token : tokens) {
            int best = 0;
            if (containsAny(symbols, token)) {
                best = SCORE_SYMBOL;
            } else if (!type.isEmpty() && type.contains(token)) {
                best = SCORE_TYPE;
            } else if (!module.isEmpty() && module.contains(token)) {
                best = SCORE_MODULE;
            } else if (path.contains(token)) {
                best = SCORE_PATH;
            }
            total += best;
        }
        return total;
    }

    /**
     * 分词：ASCII 标识符 token（≥3）+ 其骆驼峰子 token（≥4），去重保序。
     * 中文不参与（代码标识符为英文，中文分词收益低于噪声）。
     */
    static List<String> tokenize(List<String> texts) {
        Set<String> out = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (String raw : SPLIT.split(text)) {
                if (raw.length() < MIN_TOKEN) {
                    continue;
                }
                String token = raw.toLowerCase(Locale.ROOT);
                out.add(token);
                for (String part : CAMEL.split(raw)) {
                    if (part.length() >= MIN_CAMEL_TOKEN) {
                        out.add(part.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean containsAny(List<String> symbols, String token) {
        for (String symbol : symbols) {
            if (symbol.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Scored(String path, int score) {
    }
}

package com.pivotos.starter.search.api.score;

import com.pivotos.starter.search.api.document.SearchHit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 确定性 BM25 打分器（纯函数，放契约层供所有实现共用）。
 * <p>用途有三：
 * <ol>
 *   <li>simple 内存实现：自身没有「相关性」概念，靠它给出可复现的分数；</li>
 *   <li>{@code SearchProvider#searchScored} 的<b>默认实现</b>：任何没实现原生打分通道的 Provider
 *       （如 easy-es）都能先按条件取候选、再本地重排，<b>行为不至于退化成 score 恒 0</b>；</li>
 *   <li>融合前的候选归一化：把「本地 BM25 分数」与「ES _score」拉到同一套可比较的秩序里。</li>
 * </ol>
 *
 * <p><b>确定性（禁止随机）是硬要求</b>：同样的输入必须给出逐字节相同的输出，否则召回结果不可复现、
 * 评测无法对账。为此做了三件事：
 * <ol>
 *   <li>分词结果保持插入顺序（{@link LinkedHashSet} 去重），不依赖任何哈希迭代顺序；</li>
 *   <li>输入先按文档 id 升序归一，再计算 {@code avgdl / df}——<b>集合本身的顺序不影响集合统计量</b>，
 *       但浮点累加顺序会，固定顺序才能固定尾数；</li>
 *   <li>排序 tie-break 落到文档 id 升序（{@code Comparator} 稳定不够，源顺序不稳同样会漂移）。</li>
 * </ol>
 *
 * <p><b>分词策略</b>：去标点空白后取「单字 + 双字滑窗」（与 ai-kb 侧 {@code Bm25Retriever} 同口径，
 * 对齐两份 BM25 的行为，避免「切 ES 与否导致召回结果不一致」）。对中文而言双字滑窗近似 2-gram，
 * 效果好于整串精确匹配，且不需引入分词依赖。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class SearchScorer {

    /** BM25 词频饱和参数（业界标准值） */
    public static final double K1 = 1.5;

    /** BM25 长度归一参数（业界标准值） */
    public static final double B = 0.75;

    private SearchScorer() {
    }

    /**
     * 中文友好分词：去标点空白后，单字 + 双字滑窗，保持插入顺序去重。
     * <p>例如 {@code "知识库检索"} → {@code [知, 知识, 识, 识库, 库, 库检, 检, 检索, 索]}。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 去除中英文标点、空白（CJK 标点 U+3000-\u303F、全角符号 U+FF00-\uFFEF、引号 U+2018-\u201F）
        String cleaned = text.replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF\\u2018-\\u201F]", "");
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(cleaned.length() * 2);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < cleaned.length(); i++) {
            String unigram = String.valueOf(cleaned.charAt(i));
            if (seen.add(unigram)) {
                tokens.add(unigram);
            }
            if (i + 1 < cleaned.length()) {
                String bigram = cleaned.substring(i, i + 2);
                if (seen.add(bigram)) {
                    tokens.add(bigram);
                }
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * 对候选命中重打分：返回按「得分降序 / 同分 id 升序」排列的列表。
     *
     * @param hits    候选命中（分数将被忽略并重写）
     * @param keyword 查询词；空串/null 时全部得 0 分，按 id 升序返回（语义退化为「按条件取前 topK」）
     * @param fields  参与打分的字段；null/空表示全部字符串值
     * @return 新的 {@link SearchHit} 列表（不修改入参）
     */
    public static List<SearchHit> rank(List<SearchHit> hits, String keyword, Collection<String> fields) {
        // 必须是可变列表：下面要就地对它排序（List.copyOf 是只读视图，排序会抛 UnsupportedOperationException）
        List<SearchHit> input = hits == null ? new ArrayList<>() : new ArrayList<>(hits);
        // ① 输入归一：ConcurrentHashMap/HashSet 的迭代顺序不保证，浮点累加顺序必须固定
        input.sort(Comparator.comparing(SearchHit::getId, Comparator.nullsFirst(Comparator.naturalOrder())));

        List<String> terms = tokenize(keyword);
        if (terms.isEmpty()) {
            return rewrap(input);
        }

        int n = input.size();
        if (n == 0) {
            return List.of();
        }

        // ② 语料统计：文档长度与 avgdl（同样的集合 → 同样的浮点累加顺序）
        List<String> texts = new ArrayList<>(n);
        for (SearchHit hit : input) {
            texts.add(fieldText(hit.getSource(), fields));
        }
        double sumLen = 0D;
        for (String text : texts) {
            sumLen += text.length();
        }
        double avgdl = Math.max(sumLen / n, 1.0D);

        // ③ DF（逐词按固定顺序扫描）
        int[] df = new int[terms.size()];
        for (int t = 0; t < terms.size(); t++) {
            String term = terms.get(t);
            int count = 0;
            for (String text : texts) {
                if (text.contains(term)) {
                    count++;
                }
            }
            df[t] = count;
        }

        // ④ BM25
        List<SearchHit> scored = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String text = texts.get(i);
            if (text.isEmpty()) {
                continue;
            }
            double dl = text.length();
            double score = 0D;
            for (int t = 0; t < terms.size(); t++) {
                int tf = countOccurrences(text, terms.get(t));
                if (tf == 0 || df[t] == 0) {
                    continue;
                }
                double idf = Math.log(((double) (n - df[t] + 0.5D) / (df[t] + 0.5D)) + 1.0D);
                double denom = tf + K1 * (1.0D - B + B * dl / avgdl);
                score += idf * (tf * (K1 + 1.0D)) / denom;
            }
            // 分数为 0 表示「一个查询词都没命中」，对召回无意义，直接丢弃（与 ES 侧 match 命中才进 _score 一致）
            if (score > 0D) {
                scored.add(SearchHit.of(input.get(i).getId(), score, input.get(i).getSource()));
            }
        }

        return sortByScoreThenId(scored);
    }

    /**
     * 计算单个文档相对某个查询词的分数（调试/评测用）。
     */
    public static double score(String text, String keyword) {
        if (text == null || keyword == null) {
            return 0D;
        }
        List<SearchHit> single = rank(List.of(SearchHit.of("_", 0D, Map.of("_v", text))), keyword, List.of("_v"));
        return single.isEmpty() ? 0D : single.get(0).getScore();
    }

    /**
     * 取文档待打分文本：显式字段按声明顺序拼接；未指定字段时取全部字符串值，
     * <b>按 key 字典序拼接</b>（Map 迭代顺序不可依赖）。
     */
    public static String fieldText(Map<String, Object> source, Collection<String> fields) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (fields != null && !fields.isEmpty()) {
            for (String field : fields) {
                Object value = source.get(field);
                if (value != null) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(value);
                }
            }
            return sb.toString();
        }
        List<String> keys = new ArrayList<>(source.keySet());
        keys.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof CharSequence) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    public static List<SearchHit> sortByScoreThenId(List<SearchHit> hits) {
        List<SearchHit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble(SearchHit::getScore).reversed()
                .thenComparing(SearchHit::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
        return sorted;
    }

    private static List<SearchHit> rewrap(List<SearchHit> input) {
        List<SearchHit> out = new ArrayList<>(input.size());
        for (SearchHit hit : input) {
            out.add(SearchHit.of(hit.getId(), 0D, hit.getSource()));
        }
        return out;
    }

    private static int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }
}

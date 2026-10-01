package com.pivotos.starter.search.api.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）融合（纯函数，S128）。
 * <p>公式 {@code score(d) = Σ wᵢ / (k + rankᵢ(d))}：只看<b>名次</b>、不看原始分数，
 * 因此天然适配「向量相似度」与「BM25 分数」这种量纲完全不同的双通道——不必先做分数归一化。
 *
 * <p>可调项与默认值：
 * <table>
 *   <tr><td>{@link #DEFAULT_K}</td><td>60</td><td>RRF 常数 k：越大越削弱头部名次的权重</td></tr>
 *   <tr><td>{@link #DEFAULT_WEIGHT}</td><td>1.0</td><td>分通道权重：想让某一路更主导就调大（如向量 1.0 / 全文 0.8）</td></tr>
 *   <tr><td>{@link #DEFAULT_MIN_SCORE}</td><td>0.0</td><td>融合分阈值：默认 0（不丢弃）；调大可裁掉「只在通道尾部擦到」的弱候选</td></tr>
 * </table>
 *
 * <p><b>确定性</b>：融合结果先按分数降序再按 id 升序排序——仅靠降序排序时，
 * 同分文档的先后会随 Map 迭代顺序漂移，导致「同一条 query 两次召回顺序不同」。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class SearchRrf {

    /** RRF 常数默认值（Cormack 等人的原始论文取值） */
    public static final int DEFAULT_K = 60;

    /** 单通道默认权重 */
    public static final double DEFAULT_WEIGHT = 1.0D;

    /** 默认融合分阈值（0 = 不裁剪） */
    public static final double DEFAULT_MIN_SCORE = 0.0D;

    private SearchRrf() {
    }

    /**
     * 一条召回通道的排序结果。
     *
     * @param name   通道名（如 {@code vector} / {@code fulltext}），仅用于产出可观测性，不参与计算
     * @param weight 该通道权重
     * @param ids    该通道按自身分数<b>降序</b>排列的文档 id（允许存在重复，只认首次出现）
     */
    public record Channel(String name, double weight, List<String> ids) {

        public static Channel of(String name, double weight, List<String> ids) {
            return new Channel(name, weight, ids == null ? List.of() : List.copyOf(ids));
        }

        /** 等权便捷构造（权重取 {@link #DEFAULT_WEIGHT}） */
        public static Channel of(String name, List<String> ids) {
            return of(name, DEFAULT_WEIGHT, ids);
        }
    }

    /**
     * 融合结果。
     *
     * @param id       文档 id
     * @param score    RRF 融合分
     * @param channels 命中该文档的通道名（按参与顺序），便于解释「为什么这条排前面」
     */
    public record Fused(String id, double score, List<String> channels) {
    }

    /**
     * 默认参数融合（k=60、各通道权重 1.0、不裁剪），返回全部候选。
     */
    public static List<Fused> fuse(List<Channel> channels) {
        return fuse(channels, DEFAULT_K, DEFAULT_MIN_SCORE, Integer.MAX_VALUE);
    }

    /**
     * 完整融合。
     *
     * @param channels 各通道排序结果（至少语义上不该为 null，空列表返回空结果）
     * @param k        RRF 常数，必须 >= 1
     * @param minScore 融合分阈值
     * @param topK     返回条数（<= 0 视为不限）
     * @return 按融合分降序（同分按 id 升序）的候选
     */
    public static List<Fused> fuse(List<Channel> channels, int k, double minScore, int topK) {
        if (k < 1) {
            throw new IllegalArgumentException("RRF 常数 k 必须 >= 1，实际 " + k);
        }
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        Map<String, Entry> table = new LinkedHashMap<>();
        for (Channel channel : channels) {
            if (channel == null || channel.weight() < 0D) {
                throw new IllegalArgumentException("RRF 通道不合法：null 通道或负权重（channel=" + channel + "）");
            }
            LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(channel.ids());
            int rank = 0;
            for (String id : uniqueIds) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                rank++;
                Entry entry = table.computeIfAbsent(id, key -> new Entry());
                entry.score += channel.weight() / (k + rank);
                entry.channels.add(channel.name() == null ? "unnamed" : channel.name());
            }
        }

        List<Fused> results = new ArrayList<>(table.size());
        for (Map.Entry<String, Entry> e : table.entrySet()) {
            if (e.getValue().score >= minScore) {
                results.add(new Fused(e.getKey(), e.getValue().score, List.copyOf(e.getValue().channels)));
            }
        }
        results.sort(Comparator.comparingDouble(Fused::score).reversed()
                .thenComparing(Fused::id, Comparator.nullsFirst(Comparator.naturalOrder())));
        return topK <= 0 || results.size() <= topK ? results : List.copyOf(results.subList(0, topK));
    }

    private static final class Entry {
        private double score;
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();
    }
}

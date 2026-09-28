package com.pivotos.ai.coding.locate;

import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.FileSymbolTable;

import java.util.List;
import java.util.Locale;

/**
 * 精定位仲裁器（确定性打分，不交给 LLM）。
 *
 * <p>为什么仲裁必须确定性：LLM 自评置信度正是 spike K2 的失效载体——它对 Controller/接口/DTO
 * 的置信度系统性高于 Impl。若再让 LLM 做最终裁决，等于把偏差又放大一遍。故仲裁用三个信号合成：
 * <pre>
 *   score = 0.50 × 精定位自评置信度
 *         + 0.30 × 符号命中率（确定性：关键词在本文件符号表/路径/类型名的命中比例）
 *         + 0.20 × 分层因子（确定性：实现层加成 / 上层折扣，见 CodeLayer.weight）
 * </pre>
 * 三个分项全部回传（LocatePreciseVO），便于验收复盘「为什么选它」。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public final class LocateArbiter {

    /** 精定位自评置信度权重 */
    public static final double W_CONFIDENCE = 0.50;

    /** 符号命中率权重 */
    public static final double W_SYMBOL_HIT = 0.30;

    /** 分层因子权重 */
    public static final double W_LAYER = 0.20;

    /** 分层权重归一区间（与 CodeLayer 最小/最大 weight 对齐） */
    private static final double LAYER_MIN = 0.55;
    private static final double LAYER_MAX = 1.25;

    private LocateArbiter() {
    }

    /**
     * 综合打分。
     *
     * @param confidence  精定位自评置信度 0~1
     * @param symbolHit   符号命中率 0~1
     * @param layerFactor 分层因子 0~1
     * @return 综合分（越大越优）
     */
    public static double score(double confidence, double symbolHit, double layerFactor) {
        return W_CONFIDENCE * clamp01(confidence)
                + W_SYMBOL_HIT * clamp01(symbolHit)
                + W_LAYER * clamp01(layerFactor);
    }

    /** 分层因子归一：实现层 → 1，最低权重层 → 0 */
    public static double layerFactor(CodeLayer layer) {
        double weight = layer == null ? CodeLayer.UNKNOWN.weight() : layer.weight();
        return clamp01((weight - LAYER_MIN) / (LAYER_MAX - LAYER_MIN));
    }

    /**
     * 符号命中率：关键词在本文件符号名 / 路径 / 类型名上的命中比例（双向子串匹配）。
     *
     * @param keywords 关键词（意图解析产出 + 意图原文 token）
     * @param table    目标文件符号表（可为 null）
     * @return 0~1
     */
    public static double symbolHit(List<String> keywords, FileSymbolTable table) {
        if (keywords == null || keywords.isEmpty() || table == null) {
            return 0.0;
        }
        String path = lower(table.relativePath());
        String type = lower(table.typeName());
        List<String> symbols = table.symbolNames().stream().map(LocateArbiter::lower).toList();
        int hit = 0;
        for (String raw : keywords) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = lower(raw);
            boolean matched = false;
            for (String symbol : symbols) {
                if (symbol.contains(token) || token.contains(symbol)) {
                    matched = true;
                    break;
                }
            }
            if (!matched && !type.isEmpty() && (type.contains(token) || token.contains(type))) {
                matched = true;
            }
            if (!matched && path.contains(token)) {
                matched = true;
            }
            if (matched) {
                hit++;
            }
        }
        return (double) hit / keywords.size();
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

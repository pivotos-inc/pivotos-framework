package com.pivotos.ai.kb.retriever;

import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 关键词检索器：从 ai_kb_chunk 表加载文本块，在内存中计算 BM25 分数。
 *
 * <p>中文分词采用单字 + 双字滑窗策略（无额外中文分词依赖，对中文检索效果优于空格分词）。
 * 标准参数 k1=1.5, b=0.75。
 *
 * <p>设计对称于向量检索：两者结果经 {@link RrfFusion} 融合后返回。
 */
@Slf4j
@Component
public class Bm25Retriever {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final AiKbChunkMapper chunkMapper;

    public Bm25Retriever(AiKbChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * 在指定知识库中做 BM25 关键词检索。
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param topK 返回条数
     * @return BM25 分数从高到低排序的结果列表
     */
    public List<Bm25Result> search(Long kbId, String query, int topK) {
        List<AiKbChunk> chunks = chunkMapper.selectByKbId(kbId);
        if (chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }

        int n = chunks.size();
        double avgdl = chunks.stream()
                .mapToInt(c -> c.getContent() != null ? c.getContent().length() : 0)
                .average().orElse(1.0);
        if (avgdl < 1.0) {
            avgdl = 1.0;
        }

        // 计算每个查询词的 DF（包含该词的文档数）
        Map<String, Integer> dfMap = new HashMap<>();
        for (String term : queryTerms) {
            int df = (int) chunks.stream()
                    .filter(c -> c.getContent() != null && c.getContent().contains(term))
                    .count();
            dfMap.put(term, df);
        }

        // 计算每个文档的 BM25 分数
        List<Bm25Result> results = new ArrayList<>();
        for (AiKbChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isEmpty()) {
                continue;
            }
            int docLen = content.length();
            double score = 0.0;
            for (String term : queryTerms) {
                int tf = countOccurrences(content, term);
                if (tf == 0) {
                    continue;
                }
                int df = dfMap.getOrDefault(term, 0);
                double idf = Math.log((double) (n - df + 0.5) / (df + 0.5) + 1.0);
                double denom = tf + K1 * (1.0 - B + B * docLen / avgdl);
                score += idf * (tf * (K1 + 1.0)) / denom;
            }
            if (score > 0) {
                results.add(new Bm25Result(chunk, score));
            }
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        log.debug("[PivotOS-KB] BM25 检索: kbId={}, chunks={}, hits={}, topK={}",
                kbId, n, results.size(), Math.min(topK, results.size()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 中文分词：去除标点空白后，取单字 + 双字滑窗。
     * 例如 "知识库检索" → [知, 知识, 识, 识库, 库, 库检, 检, 检索, 索]
     */
    List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        // 去除中英文标点、空白（CJK 标点 U+3000-\u303F、全角符号 U+FF00-\uFFEF、引号 U+2018-\u201F）
        String cleaned = text.replaceAll("[\\s\\p{Punct}\u3000-\u303F\uFF00-\uFFEF\u2018-\u201F]", "");
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < cleaned.length(); i++) {
            // 单字
            String unigram = String.valueOf(cleaned.charAt(i));
            if (seen.add(unigram)) {
                tokens.add(unigram);
            }
            // 双字滑窗
            if (i + 1 < cleaned.length()) {
                String bigram = cleaned.substring(i, i + 2);
                if (seen.add(bigram)) {
                    tokens.add(bigram);
                }
            }
        }
        return tokens;
    }

    /** 统计 term 在 text 中出现的次数 */
    private int countOccurrences(String text, String term) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /** BM25 检索结果 */
    public record Bm25Result(AiKbChunk chunk, double score) {}
}

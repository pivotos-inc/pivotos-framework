package com.pivotos.ai.kb.retriever;

import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）：将向量检索与 BM25 检索结果融合排序。
 *
 * <p>RRF 公式：score(d) = Σ 1/(k + rank_i)，k=60（业界标准常量）。
 * 同一文本块（按 content 前 100 字 MD5 匹配）在两路命中时分数累加，
 * 仅一路命中时取单项分数。
 *
 * <p>不依赖外部 reranker API，纯排序融合，无额外推理成本。
 */
@Component
public class RrfFusion {

    private static final int K = 60;

    /**
     * 融合向量检索与 BM25 检索结果。
     *
     * @param vectorResults 向量检索结果（已按相似度排序）
     * @param bm25Results    BM25 检索结果（已按 BM25 分数排序）
     * @param topK           返回条数
     * @return RRF 融合排序后的 topK 条结果
     */
    public List<FusedResult> fuse(List<Document> vectorResults,
                                  List<Bm25Retriever.Bm25Result> bm25Results,
                                  int topK) {
        Map<String, FusedEntry> map = new LinkedHashMap<>();

        // 向量检索结果：记录排名
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String hash = hashOf(doc.getText());
            FusedEntry entry = map.computeIfAbsent(hash, k -> new FusedEntry(doc.getText(), doc.getMetadata()));
            entry.vectorRank = i + 1;
            // 向量结果的 metadata 更完整（含 file_name），优先保留
            if (entry.metadata == null || entry.metadata.isEmpty()) {
                entry.metadata = doc.getMetadata();
            }
        }

        // BM25 检索结果：记录排名
        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25Retriever.Bm25Result result = bm25Results.get(i);
            AiKbChunk chunk = result.chunk();
            String hash = hashOf(chunk.getContent());
            FusedEntry entry = map.computeIfAbsent(hash, k -> new FusedEntry(chunk.getContent(), null));
            entry.bm25Rank = i + 1;
            entry.chunk = chunk;
        }

        // 计算 RRF 分数并排序
        List<FusedResult> results = new ArrayList<>();
        for (FusedEntry entry : map.values()) {
            double score = 0.0;
            if (entry.vectorRank > 0) {
                score += 1.0 / (K + entry.vectorRank);
            }
            if (entry.bm25Rank > 0) {
                score += 1.0 / (K + entry.bm25Rank);
            }
            results.add(new FusedResult(entry.content, entry.metadata, entry.chunk, score,
                    entry.vectorRank, entry.bm25Rank));
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /** 计算 content 前 100 字的 MD5 hash（用于跨检索路匹配同一文本块） */
    private String hashOf(String content) {
        if (content == null || content.isEmpty()) {
            return "empty";
        }
        String prefix = content.length() > 100 ? content.substring(0, 100) : content;
        return md5Hex(prefix);
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /** 融合过程中的内部条目 */
    private static class FusedEntry {
        String content;
        Map<String, Object> metadata;
        AiKbChunk chunk;
        int vectorRank;
        int bm25Rank;

        FusedEntry(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }
    }

    /**
     * 融合结果：content + metadata（含 file_name 等） + chunk（可能为 null）+ RRF 分数。
     *
     * @param content  文本块内容
     * @param metadata 元数据（向量检索结果携带，BM25 独有结果可能为 null）
     * @param chunk    BM25 文本块（向量独有结果为 null）
     * @param score    RRF 融合分数
     */
    public record FusedResult(String content, Map<String, Object> metadata,
                              AiKbChunk chunk, double score,
                              int vectorRank, int bm25Rank) {}
}

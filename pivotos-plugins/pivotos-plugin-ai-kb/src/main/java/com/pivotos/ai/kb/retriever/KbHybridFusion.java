package com.pivotos.ai.kb.retriever;

import com.pivotos.ai.kb.config.KbChunkSearchProperties;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.starter.search.api.fusion.SearchRrf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双通道融合（S128）：向量通道 + 全文通道 → RRF。
 * <p>融合算法下沉到契约层 {@link SearchRrf}（纯函数、有单测），本类只做三件事：
 * <ol>
 *   <li>把两路结果<b>归一到同一套 id</b>（文本块内容 hash），否则「同一块内容」在两路里是两个身份，
 *       RRF 的「两路都命中应加分」就永远触发不了——{@code RrfFusion} 旧实现也是按内容 hash 对齐的，
 *       这里沿用同一口径，避免换了通道后对齐规则不一致；</li>
 *   <li>把配置（k / 双路权重 / 阈值）喂进去；</li>
 *   <li>把融合结果<b>还原成实体</b>（content + metadata + chunk），供后续 rerank 与问答使用。</li>
 * </ol>
 *
 * <p><b>为什么用 RRF 而不是加权求和</b>：向量相似度（0~1）与 BM25（无上界）量纲不同，
 * 加权求和必须先归一化，而归一化的参数会随语料漂移；RRF 只看名次，天然免归一化。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Slf4j
@Component
public class KbHybridFusion {

    private final KbChunkSearchProperties properties;

    public KbHybridFusion(KbChunkSearchProperties properties) {
        this.properties = properties == null ? new KbChunkSearchProperties() : properties;
    }

    /**
     * 融合两路结果。
     *
     * @param vectorResults  向量通道结果（已按相似度降序）
     * @param fullTextChunks 全文通道结果（已按 BM25 分数降序）
     * @param topK           返回条数
     */
    public List<RrfFusion.FusedResult> fuse(List<Document> vectorResults,
                                            List<AiKbChunk> fullTextChunks,
                                            int topK) {
        KbChunkSearchProperties.Fusion cfg = properties.getFusion() == null
                ? new KbChunkSearchProperties.Fusion() : properties.getFusion();

        List<String> vectorIds = new ArrayList<>();
        Map<String, Document> vectorById = new LinkedHashMap<>();
        Map<String, Integer> vectorRank = new LinkedHashMap<>();
        if (vectorResults != null) {
            for (Document d : vectorResults) {
                String id = idOf(d);
                if (vectorById.putIfAbsent(id, d) == null) {
                    vectorIds.add(id);
                    vectorRank.put(id, vectorIds.size());
                }
            }
        }

        List<String> fullTextIds = new ArrayList<>();
        Map<String, AiKbChunk> chunkById = new LinkedHashMap<>();
        Map<String, Integer> fullTextRank = new LinkedHashMap<>();
        if (fullTextChunks != null) {
            for (AiKbChunk chunk : fullTextChunks) {
                if (chunk == null) {
                    continue;
                }
                String id = idOf(chunk);
                if (chunkById.putIfAbsent(id, chunk) == null) {
                    fullTextIds.add(id);
                    fullTextRank.put(id, fullTextIds.size());
                }
            }
        }
        if (vectorIds.isEmpty() && fullTextIds.isEmpty()) {
            return List.of();
        }

        List<SearchRrf.Fused> fused = SearchRrf.fuse(List.of(
                        SearchRrf.Channel.of("vector", cfg.getVectorWeight(), vectorIds),
                        SearchRrf.Channel.of("fulltext", cfg.getFullTextWeight(), fullTextIds)),
                cfg.getK(), cfg.getMinScore(), topK);

        List<RrfFusion.FusedResult> results = new ArrayList<>(fused.size());
        for (SearchRrf.Fused item : fused) {
            Document doc = vectorById.get(item.id());
            AiKbChunk chunk = chunkById.get(item.id());
            String content = doc != null ? doc.getText() : (chunk == null ? null : chunk.getContent());
            if (content == null) {
                continue;
            }
            results.add(new RrfFusion.FusedResult(content,
                    doc == null ? null : doc.getMetadata(),
                    chunk,
                    item.score(),
                    vectorRank.getOrDefault(item.id(), 0),
                    fullTextRank.getOrDefault(item.id(), 0),
                    null));
        }
        log.debug("[PivotOS][search] RRF 融合：vector={}, fulltext={}, fused={}, k={}, w=({}/{})",
                vectorIds.size(), fullTextIds.size(), results.size(), cfg.getK(),
                cfg.getVectorWeight(), cfg.getFullTextWeight());
        return results;
    }

    /**
     * 向量结果的对齐 id：优先用写入时埋的 {@code chunk_hash}，老数据没有则按内容前 100 字取 MD5
     * （与 {@code RrfFusion} 旧口径一致，保证「有没有 chunk_hash」不改变融合行为）。
     */
    private static String idOf(Document doc) {
        if (doc == null) {
            return "empty";
        }
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object hash = metadata.get(RrfFusion.META_CHUNK_HASH);
            if (hash != null && !String.valueOf(hash).isBlank()) {
                return String.valueOf(hash);
            }
        }
        return RrfFusion.hashOf(doc.getText());
    }

    private static String idOf(AiKbChunk chunk) {
        if (chunk.getContentHash() != null && !chunk.getContentHash().isBlank()) {
            return chunk.getContentHash();
        }
        return RrfFusion.hashOf(chunk.getContent());
    }
}

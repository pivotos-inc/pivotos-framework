package com.pivotos.ai.kb.retriever;

import com.pivotos.ai.kb.config.KbChunkSearchProperties;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双通道 RRF 融合（S128）。
 * <p>三条硬断言：
 * <ol>
 *   <li><b>同一文本块在两路命中必须算一条并加分</b>——这是 RRF 存在的意义，
 *       也是「向量通道与全文通道没对齐身份」时最先坏掉的地方；</li>
 *   <li><b>权重与阈值可配置且有默认值</b>——不配 yml 也要能跑，配了必须生效；</li>
 *   <li><b>结果可还原成实体</b>（content + metadata + chunk），否则后续 rerank 与引用没得用。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class KbHybridFusionTest {

    private KbChunkSearchProperties properties;
    private KbHybridFusion fusion;

    @BeforeEach
    void setUp() {
        properties = new KbChunkSearchProperties();
        fusion = new KbHybridFusion(properties);
    }

    @Test
    void shouldMergeSameChunkHitByBothChannels() {
        AiKbChunk chunk = chunk(1L, "知识库检索使用说明");
        Document doc = new Document(chunk.getContent(), Map.of(RrfFusion.META_CHUNK_HASH, chunk.getContentHash()));

        List<RrfFusion.FusedResult> fused = fusion.fuse(List.of(doc), List.of(chunk), 10);

        assertEquals(1, fused.size(), "同一文本块在两路命中必须算作一条（身份对齐靠内容 hash）");
        assertEquals(1, fused.get(0).vectorRank());
        assertEquals(1, fused.get(0).bm25Rank());
        // 两路都是第 1 名：score = 1/(60+1) + 1/(60+1)
        assertEquals(2.0D / 61.0D, fused.get(0).score(), 1e-9,
                "两路都命中的分数必须高于只命中一路（RRF 的增益就在这里）");
        assertEquals(chunk, fused.get(0).chunk(), "融合结果必须还原出文本块实体，供后续 rerank/引用使用");
    }

    @Test
    void shouldKeepFullTextOnlyResultWithoutMetadata() {
        AiKbChunk chunk = chunk(2L, "报销流程说明");

        List<RrfFusion.FusedResult> fused = fusion.fuse(List.of(), List.of(chunk), 10);

        assertEquals(1, fused.size());
        assertEquals(chunk.getContent(), fused.get(0).content());
        assertNull(fused.get(0).metadata(), "只有全文通道命中时没有向量元数据，允许为 null");
        assertEquals(0, fused.get(0).vectorRank());
        assertEquals(1.0D / 61.0D, fused.get(0).score(), 1e-9);
    }

    @Test
    void shouldKeepVectorOnlyResultWithoutChunk() {
        Document doc = new Document("纯向量命中的内容", Map.of("kb_id", "5"));

        List<RrfFusion.FusedResult> fused = fusion.fuse(List.of(doc), List.of(), 10);

        assertEquals(1, fused.size());
        assertEquals("纯向量命中的内容", fused.get(0).content());
        assertNull(fused.get(0).chunk());
        assertEquals(Map.of("kb_id", "5"), fused.get(0).metadata());
    }

    @Test
    void shouldHonorConfiguredWeights() {
        AiKbChunk chunk = chunk(3L, "知识库配置");
        Document doc = new Document("向量独有的内容", Map.of("kb_id", "5"));
        properties.getFusion().setVectorWeight(3.0D);
        properties.getFusion().setFullTextWeight(1.0D);

        List<RrfFusion.FusedResult> fused = fusion.fuse(List.of(doc), List.of(chunk), 10);

        assertEquals("向量独有的内容", fused.get(0).content(), "向量权重 3.0 时向量独有结果应居首");
        assertEquals(3.0D / 61.0D, fused.get(0).score(), 1e-9);
    }

    @Test
    void shouldHonorConfiguredMinScoreAndTopK() {
        AiKbChunk first = chunk(4L, "知识库一");
        AiKbChunk second = chunk(5L, "知识库二");
        AiKbChunk third = chunk(6L, "知识库三");

        assertEquals(2, fusion.fuse(List.of(), List.of(first, second, third), 2).size(), "topK 截断");

        // 第 1 名 1/61≈0.01639 留下，第 2 名 1/62≈0.01613 被裁（阈值取两者之间）
        properties.getFusion().setMinScore(0.0162D);
        assertEquals(1, fusion.fuse(List.of(), List.of(first, second, third), 10).size(),
                "融合分阈值必须真实裁剪");
    }

    @Test
    void shouldFallbackToContentMd5WhenHashMissing() {
        AiKbChunk chunk = chunk(7L, "没有埋 hash 的老数据");
        chunk.setContentHash(null);
        Document doc = new Document(chunk.getContent(), Map.of());

        List<RrfFusion.FusedResult> fused = fusion.fuse(List.of(doc), List.of(chunk), 10);

        assertEquals(1, fused.size(), "没有 chunk_hash 时按内容前 100 字 MD5 兜底对齐（与旧口径一致）");
    }

    @Test
    void shouldReturnEmptyWhenBothChannelsEmpty() {
        assertTrue(fusion.fuse(List.of(), List.of(), 10).isEmpty());
        assertTrue(fusion.fuse(null, null, 10).isEmpty());
    }

    private static AiKbChunk chunk(Long id, String content) {
        AiKbChunk chunk = new AiKbChunk();
        chunk.setId(id);
        chunk.setKbId(1L);
        chunk.setDocId(1L);
        chunk.setContent(content);
        chunk.setContentHash(RrfFusion.hashOf(content));
        return chunk;
    }
}

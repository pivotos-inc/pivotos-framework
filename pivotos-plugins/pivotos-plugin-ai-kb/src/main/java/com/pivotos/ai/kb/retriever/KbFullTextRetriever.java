package com.pivotos.ai.kb.retriever;

import com.pivotos.ai.kb.config.KbChunkSearchProperties;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.ai.kb.search.KbChunkSearchSupport;
import com.pivotos.starter.search.api.template.ScoredRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 混合检索的<b>全文通道</b>（S128）：优先走 search 抽象，不可用时回退库内 BM25。
 * <p>两条来源语义一致（都是「按关键词给文本块打分，返回降序 topK」），差别只在执行位置：
 * <table>
 *   <tr><th>来源</th><th>simple</th><th>es-java</th><th>库内（回退）</th></tr>
 *   <tr><td>执行位置</td><td>进程内确定性 BM25</td><td>ES 引擎侧 BM25</td><td>全量拉进内存算 BM25</td></tr>
 *   <tr><td>代价</td><td>重启即失（靠回灌补）</td><td>近实时 + 网络</td><td>每问一次全表扫</td></tr>
 * </table>
 *
 * <p><b>为什么默认允许回退</b>：召回是「增强」而非「强依赖」。索引冷启动未完成、ES 暂时不可用
 * 时不该让问答直接搜不到——回退后结果质量略降但功能不缺。想强制只用 search 通道时把
 * {@code pivotos.search.kb-chunk.fallback-to-db-bm25} 置 false。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Slf4j
@Component
public class KbFullTextRetriever {

    private static final String TAG = "[PivotOS][search]";

    private final KbChunkSearchSupport support;
    private final Bm25Retriever bm25Retriever;
    private final KbChunkSearchProperties properties;

    public KbFullTextRetriever(KbChunkSearchSupport support,
                               Bm25Retriever bm25Retriever,
                               KbChunkSearchProperties properties) {
        this.support = support;
        this.bm25Retriever = bm25Retriever;
        this.properties = properties == null ? new KbChunkSearchProperties() : properties;
    }

    /**
     * 全文召回。
     *
     * @return 按相关度降序的文本块（可能为空）
     */
    public List<AiKbChunk> retrieve(Long kbId, String query, int topK) {
        if (support.enabled()) {
            List<ScoredRecord<AiKbChunk>> scored = support.searchScored(kbId, query, topK);
            if (!scored.isEmpty()) {
                log.debug("{} 全文通道走 search 抽象：kbId={}, hits={}", TAG, kbId, scored.size());
                return scored.stream().map(ScoredRecord::getEntity).toList();
            }
            log.debug("{} 全文通道 search 侧无命中，准备回退：kbId={}", TAG, kbId);
        }
        if (!properties.isFallbackToDbBm25()) {
            return List.of();
        }
        if (bm25Retriever == null) {
            return List.of();
        }
        return bm25Retriever.search(kbId, query, topK).stream()
                .map(Bm25Retriever.Bm25Result::chunk)
                .toList();
    }
}

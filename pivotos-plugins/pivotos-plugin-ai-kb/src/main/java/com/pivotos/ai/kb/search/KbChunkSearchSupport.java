package com.pivotos.ai.kb.search;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.config.KbChunkSearchProperties;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.template.ScoredRecord;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.api.template.SearchScoredPage;
import com.pivotos.starter.search.api.template.SearchTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库<b>文本块全文索引</b>支撑（S128）：RAG 召回的关键词通道由此接入 search 抽象。
 * <p>S122 只把「文档元数据」接进了检索（{@link KbDocSearchSupport}），
 * 召回侧的关键词通道仍是从 {@code ai_kb_chunk} 表全量拉进内存算 BM25（{@code Bm25Retriever}）——
 * 这正是 S122 遗留②。本类把这条通道换成：
 * <pre>
 *   simple  实现 → 内存里确定性 BM25（与库内 BM25 同 k1/b 参数，行为可比）
 *   es-java 实现 → ES 引擎侧真 BM25（multi_match），不再把全量块拉回本地
 * </pre>
 *
 * <p><b>为什么必须显式建全文映射</b>：确定性索引把字符串一律落成 keyword（S122 缺陷①的修法），
 * keyword 上打 multi_match 只能整值命中；故打分通道走
 * {@code createFullTextIndexIfAbsent}，把 {@code content} 单独落成 text。
 *
 * <p><b>租户口径</b>：与库内路径一致——{@code ai_kb_chunk} 按 kb_id 做知识库级隔离，
 * 检索条件只带 kb_id/doc_id，不带 tenant_id（与 {@code AiKbChunkMapper} 的
 * {@code @InterceptorIgnore(tenantLine)} 同口径）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class KbChunkSearchSupport {

    private static final Logger log = LoggerFactory.getLogger(KbChunkSearchSupport.class);

    /** 删索引只能按 id，故「先查后删」；这里给单次删除的查询上限 */
    private static final int DELETE_BATCH = 1000;

    public static final String INDEX_NAME = SearchIndexNameResolver.resolve(AiKbChunk.class);

    private final ObjectProvider<SearchTemplate> templateProvider;
    private final KbChunkSearchProperties properties;
    private final AiKbChunkMapper chunkMapper;
    /** 全文映射是否已建（幂等，避免每次写索引都打一次 ES） */
    private final AtomicBoolean mappingEnsured = new AtomicBoolean(false);

    public KbChunkSearchSupport(ObjectProvider<SearchTemplate> templateProvider,
                                KbChunkSearchProperties properties,
                                AiKbChunkMapper chunkMapper) {
        this.templateProvider = templateProvider;
        this.properties = properties == null ? new KbChunkSearchProperties() : properties;
        this.chunkMapper = chunkMapper;
    }

    public boolean enabled() {
        return properties.isEnabled() && templateProvider.getIfAvailable() != null;
    }

    /**
     * 批量写索引（只对已有主键的块生效：无主键无法确定文档 id，覆盖写无从谈起）
     */
    public void indexBatch(List<AiKbChunk> chunks) {
        if (!enabled() || chunks == null || chunks.isEmpty()) {
            return;
        }
        List<AiKbChunk> valid = new ArrayList<>(chunks.size());
        for (AiKbChunk chunk : chunks) {
            if (chunk != null && chunk.getId() != null) {
                valid.add(chunk);
            }
        }
        if (valid.isEmpty()) {
            log.warn("[PivotOS][search] 文本块 {} 条全部缺少主键，跳过写索引（检查批量插入是否回填了自增 id）",
                    chunks.size());
            return;
        }
        try {
            // 必须「先建全文映射，再写数据」：写入路径只会建 keyword 索引，
            // 一旦文档先落库，content 就被 keyword 动态模板定型，之后再补 text 会被 ES 拒绝
            // （症状：全文通道恒无命中，且只有一条 WARN，极难发现——本轮起服冒烟就踩到了）
            ensureFullTextMapping();
            template().indexBatch(valid);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块写索引失败（不影响业务）：size={}, reason={}",
                    valid.size(), e.getMessage());
        }
    }

    /**
     * 打分召回：返回按分数降序的文本块（分数见 {@link ScoredRecord#getScore()}）。
     *
     * @return 命中列表；<b>空列表表示不可用或无命中</b>，调用方据此回退库内 BM25
     */
    public List<ScoredRecord<AiKbChunk>> searchScored(Long kbId, String keyword, int topK) {
        if (!enabled() || kbId == null) {
            return List.of();
        }
        try {
            LambdaSearchQuery<AiKbChunk> query = LambdaSearchQuery.of(AiKbChunk.class)
                    .eq(AiKbChunk::getKbId, kbId)
                    .keyword(keyword)
                    .fullTextFields(properties.getFields().toArray(new String[0]))
                    .minScore(properties.getMinScore())
                    .candidateWindow(properties.getCandidateWindow())
                    .limit(Math.max(topK, 1));
            SearchScoredPage<AiKbChunk> page = template().searchScored(query);
            return page == null ? List.of() : page.getRecords();
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块打分召回失败，回退库内 BM25：kbId={}, reason={}", kbId, e.getMessage());
            return List.of();
        }
    }

    /** 删除指定文档的全部文本块索引 */
    public void deleteByDocId(Long docId) {
        if (!enabled() || docId == null) {
            return;
        }
        deleteByQuery(LambdaSearchQuery.of(AiKbChunk.class).eq(AiKbChunk::getDocId, docId).limit(DELETE_BATCH));
    }

    /** 删除指定知识库的全部文本块索引 */
    public void deleteByKbId(Long kbId) {
        if (!enabled() || kbId == null) {
            return;
        }
        deleteByQuery(LambdaSearchQuery.of(AiKbChunk.class).eq(AiKbChunk::getKbId, kbId).limit(DELETE_BATCH));
    }

    /**
     * 冷启动回灌：索引为空时从库内灌一次（simple 实现重启即失，必须靠它补回）。
     *
     * @return 回灌条数；0 表示跳过，-1 表示失败（不影响起服）
     */
    public int bootstrapIfEmpty() {
        if (!enabled() || !properties.isBootstrapOnStart()) {
            return 0;
        }
        try {
            // 先建全文映射再计数：count 会顺手把索引建出来（keyword-only），
            // 之后再补 text 就得走「追加映射」，而追加对已被 keyword 定型的字段无效
            ensureFullTextMapping();
            long indexed = countIndexed();
            if (indexed != 0L) {
                return 0;
            }
            int maxRows = Math.max(properties.getBootstrapMaxRows(), 1);
            // 用 MP 分页而不是 last("LIMIT n")：PIVOTOS SQL 十条禁 last()，ArchUnit A10 会直接卡住
            Page<AiKbChunk> page = chunkMapper.selectPage(new Page<>(1, maxRows),
                    Wrappers.<AiKbChunk>lambdaQuery().orderByDesc(AiKbChunk::getCreateTime));
            List<AiKbChunk> chunks = page == null ? null : page.getRecords();
            if (chunks == null || chunks.isEmpty()) {
                return 0;
            }
            indexBatch(chunks);
            log.info("[PivotOS][search] 文本块索引回灌 {} 条（索引 {}）", chunks.size(), INDEX_NAME);
            return chunks.size();
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块索引回灌失败（不影响起服）：reason={}", e.getMessage());
            return -1;
        }
    }

    /** 索引内文本块数；-1 表示不可用 */
    public long countIndexed() {
        if (!enabled()) {
            return -1L;
        }
        try {
            return template().count(LambdaSearchQuery.of(AiKbChunk.class).limit(1));
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块索引计数失败：reason={}", e.getMessage());
            return -1L;
        }
    }

    // ==================== 内部 ====================

    /**
     * 先按条件查出 id 再逐条删：<b>SPI 只提供按 id 删除</b>，没有「按条件删」，
     * 故这里查一批删一批（上限 {@link #DELETE_BATCH} 条，超出部分留待下次触发时再删——
     * 索引残留在召回侧表现为「多召回几条」，比删除失败阻塞业务可接受）。
     */
    private void deleteByQuery(LambdaSearchQuery<AiKbChunk> query) {
        try {
            SearchTemplate template = template();
            SearchPage<AiKbChunk> page = template.search(query);
            if (page == null || page.getRecords().isEmpty()) {
                return;
            }
            for (AiKbChunk chunk : page.getRecords()) {
                if (chunk != null && chunk.getId() != null) {
                    template.delete(INDEX_NAME, String.valueOf(chunk.getId()));
                }
            }
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块删索引失败（不影响业务）：reason={}", e.getMessage());
        }
    }

    /**
     * 建全文映射（幂等，进程内只做一次；失败不抛，下次写入再试）。
     */
    private void ensureFullTextMapping() {
        if (mappingEnsured.get()) {
            return;
        }
        try {
            template().createFullTextIndexIfAbsent(AiKbChunk.class,
                    properties.getFields().toArray(new String[0]));
            mappingEnsured.set(true);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 文本块全文映射创建失败，全文通道可能退化为整值命中：reason={}", e.getMessage());
        }
    }

    private SearchTemplate template() {
        return templateProvider.getObject();
    }
}

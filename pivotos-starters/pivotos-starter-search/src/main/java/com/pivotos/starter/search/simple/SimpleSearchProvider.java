package com.pivotos.starter.search.simple;

import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.query.SearchOrder;
import com.pivotos.starter.search.api.score.ScoredSearchRequest;
import com.pivotos.starter.search.api.score.SearchScorer;
import com.pivotos.starter.search.api.spi.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * simple 内存兜底实现。
 * <p>口径同 {@code SimpleVectorStoreProvider}：不是「退化成不检索」，而是<b>进程内真能检出的内存实现</b>，
 * 让无 ES 环境也能起服、能跑 IT。代价是单进程可见、重启即失、全量线性扫描——
 * 生产必须引 Easy-ES / elasticsearch-java 实现（docsite 手册已标注）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class SimpleSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SimpleSearchProvider.class);

    /** indexName → (docId → document) */
    private final ConcurrentMap<String, ConcurrentMap<String, SearchDocument>> store = new ConcurrentHashMap<>();

    @Override
    public SearchProviderType type() {
        return SearchProviderType.SIMPLE;
    }

    @Override
    public void index(SearchDocument document) {
        requireDocument(document);
        bucket(document.getIndexName()).put(document.getId(), document);
    }

    @Override
    public void indexBatch(List<SearchDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        documents.forEach(this::index);
    }

    @Override
    public void delete(String indexName, String id) {
        ConcurrentMap<String, SearchDocument> bucket = store.get(indexName);
        if (bucket != null) {
            bucket.remove(id);
        }
    }

    @Override
    public SearchResult search(SearchRequest request) {
        List<Map.Entry<String, SearchDocument>> matched = filter(request);
        matched.sort(byOrders(request.getOrders()));
        int offset = request.offset();
        int size = Math.max(request.getPageSize(), 1);
        List<SearchHit> hits = new ArrayList<>();
        for (int i = offset; i < matched.size() && hits.size() < size; i++) {
            Map.Entry<String, SearchDocument> e = matched.get(i);
            hits.add(SearchHit.of(e.getKey(), 0D, e.getValue().copySource()));
        }
        return SearchResult.of(hits, matched.size());
    }

    /**
     * 打分召回：内存实现没有「相关性」概念，靠 {@link SearchScorer} 给出<b>确定性 BM25</b>。
     * <p>为什么必须自己覆写而不是吃 SPI 的默认实现：默认实现为了兼容所有实现，走的是
     * 「先 search 取候选 → 本地重排」，会额外拷一遍 source；simple 本来就是内存结构，
     * 直接在同一份数据上打分更快，且<b>打分窗口与截断顺序一目了然</b>（便于单测钉死确定性）。
     *
     * <p>确定性三要素（缺一即不可复现，RAG 评测无法对账）：
     * <ol>
     *   <li>输入按文档 id 升序归一（{@code ConcurrentHashMap} 迭代顺序不保证）；</li>
     *   <li>BM25 参数与分词固定（单字 + 双字滑窗，无随机、无哈希序依赖）；</li>
     *   <li>同分按 id 升序 tie-break。</li>
     * </ol>
     */
    @Override
    public SearchResult searchScored(ScoredSearchRequest request) {
        if (request == null) {
            return SearchResult.empty();
        }
        ConcurrentMap<String, SearchDocument> bucket = store.get(request.getIndexName());
        if (bucket == null) {
            log.debug("[PivotOS][search] simple 索引 {} 不存在，打分召回返回空结果", request.getIndexName());
            return SearchResult.empty();
        }
        // ① 先按条件过滤，再按 id 升序取前 candidateWindow 条（窗口即「最多给多少文档打分」）
        List<Map.Entry<String, SearchDocument>> matched = new ArrayList<>();
        for (Map.Entry<String, SearchDocument> e : bucket.entrySet()) {
            if (SimpleCriteriaMatcher.matches(e.getValue().getSource(), request.getCriteria())) {
                matched.add(e);
            }
        }
        matched.sort(Comparator.comparing(Map.Entry::getKey));
        int window = Math.min(matched.size(), request.getCandidateWindow());

        List<SearchHit> candidates = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            Map.Entry<String, SearchDocument> e = matched.get(i);
            candidates.add(SearchHit.of(e.getKey(), 0D, e.getValue().copySource()));
        }

        // ② 确定性 BM25 重排（无关键词时 rank 返回全 0 分、按 id 升序，即「按条件取前 topK」）
        List<SearchHit> ranked = SearchScorer.rank(candidates, request.getKeyword(), request.getFields());

        // ③ 阈值裁剪 → 截断 topK。total 取「过阈值」的数量而非窗口大小，与 ES 侧 min_score 语义一致
        List<SearchHit> kept = new ArrayList<>(ranked.size());
        for (SearchHit hit : ranked) {
            if (hit.getScore() >= request.getMinScore()) {
                kept.add(hit);
            }
        }
        List<SearchHit> top = kept.size() <= request.getTopK()
                ? kept : new ArrayList<>(kept.subList(0, request.getTopK()));
        return SearchResult.of(top, kept.size());
    }

    @Override
    public long count(SearchRequest request) {
        return filter(request).size();
    }

    @Override
    public boolean existsIndex(String indexName) {
        return store.containsKey(indexName);
    }

    @Override
    public void createIndexIfAbsent(String indexName) {
        bucket(indexName);
    }

    /**
     * 清空全部索引（测试与运维用，生产 simple 实现不建议使用故无副作用顾虑）
     */
    public void clear() {
        store.clear();
    }

    // ==================== 内部 ====================

    private List<Map.Entry<String, SearchDocument>> filter(SearchRequest request) {
        ConcurrentMap<String, SearchDocument> bucket = store.get(request.getIndexName());
        // 必须返回可变列表：search() 会就地对它排序，返回 List.of() 会抛 UnsupportedOperationException
        List<Map.Entry<String, SearchDocument>> matched = new ArrayList<>();
        if (bucket == null) {
            log.debug("[PivotOS][search] simple 索引 {} 不存在，返回空结果", request.getIndexName());
            return matched;
        }
        for (Map.Entry<String, SearchDocument> e : bucket.entrySet()) {
            if (SimpleCriteriaMatcher.matches(e.getValue().getSource(), request.getCriteria())) {
                matched.add(e);
            }
        }
        return matched;
    }

    private Comparator<Map.Entry<String, SearchDocument>> byOrders(List<SearchOrder> orders) {
        Comparator<Map.Entry<String, SearchDocument>> comparator = null;
        if (orders != null) {
            for (SearchOrder order : orders) {
                Comparator<Map.Entry<String, SearchDocument>> c = Comparator.comparing(
                        e -> e.getValue().getSource() == null ? null : e.getValue().getSource().get(order.getField()),
                        Comparator.nullsLast(SimpleSearchProvider::compareNullSafe));
                if (!order.isAsc()) {
                    c = c.reversed();
                }
                comparator = comparator == null ? c : comparator.thenComparing(c);
            }
        }
        return comparator == null ? Comparator.comparing(Map.Entry::getKey) : comparator;
    }

    /**
     * 排序比较：null 已在 nullsLast 处理，此处只处理「可比值」与「不可比值」。
     * 不可比（compare 返回 null）时视为相等，避免 Comparator 抛异常打断检索。
     */
    private static int compareNullSafe(Object a, Object b) {
        Integer r = SimpleCriteriaMatcher.compare(a, b);
        return r == null ? 0 : r;
    }

    private ConcurrentMap<String, SearchDocument> bucket(String indexName) {
        return store.computeIfAbsent(indexName, k -> new ConcurrentHashMap<>());
    }

    private void requireDocument(SearchDocument document) {
        if (document == null || document.getIndexName() == null || document.getId() == null) {
            throw new IllegalArgumentException("搜索文档不完整：indexName 与 id 均不可为空");
        }
    }
}

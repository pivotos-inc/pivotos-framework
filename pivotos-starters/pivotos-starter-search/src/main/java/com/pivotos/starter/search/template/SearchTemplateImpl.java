package com.pivotos.starter.search.template;

import com.pivotos.starter.metrics.support.BusinessMetrics;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.score.ScoredSearchRequest;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.api.template.ScoredRecord;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.api.template.SearchScoredPage;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.starter.search.route.SearchProviderFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 搜索门面默认实现。
 *
 * <h3>指标埋点（V3.1-F · P1-B8-1）</h3>
 * 全部六个操作（search / searchScored / count / index / indexBatch / delete）都在这里埋点，
 * 因为本类是<b>检索的唯一门面</b>：业务侧不直接接触 {@link SearchProvider}，
 * 在这里收敛就不需要在每个 Provider 实现里各写一遍（三个实现各写一遍必然漂移）。
 * tag 只有两个维度且都是有限集：{@code type}（生效实现）/ {@code operation}（操作名）——
 * 索引名与文档 id 绝不当 tag（高基数会把 Prometheus 打挂）。
 *
 * @author PivotOS Team
 * @since 2.16.0（V3.1-F 增加指标埋点）
 */
public class SearchTemplateImpl implements SearchTemplate {

    private final SearchProviderFactory factory;
    private final SearchProperties properties;
    /** 指标埋点；构造未传时降级为 no-op（{@link BusinessMetrics} 的 registry == null 形态） */
    private final BusinessMetrics businessMetrics;

    public SearchTemplateImpl(SearchProviderFactory factory, SearchProperties properties) {
        this(factory, properties, null);
    }

    /**
     * @param businessMetrics 指标门面，可为 null（null 时本类零埋点行为，单测与降级装配用）
     */
    public SearchTemplateImpl(SearchProviderFactory factory, SearchProperties properties,
                              BusinessMetrics businessMetrics) {
        this.factory = factory;
        this.properties = properties == null ? new SearchProperties() : properties;
        this.businessMetrics = businessMetrics == null ? new BusinessMetrics(null) : businessMetrics;
    }

    @Override
    public <T> SearchPage<T> search(LambdaSearchQuery<T> query) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            requireQuery(query);
            String indexName = resolveIndex(query);
            SearchProvider provider = factory.get();
            provider.createIndexIfAbsent(indexName);

            SearchResult result = provider.search(SearchRequest.of(indexName, query.getCriteria(),
                    query.getOrders(), query.getPageNum(), query.getPageSize()));

            List<T> records = new ArrayList<>();
            for (SearchHit hit : result.getHits()) {
                T entity = SearchEntityMapper.fromMap(hit.getSource(), query.getEntityType());
                if (entity != null) {
                    records.add(entity);
                }
            }
            ok = true;
            return SearchPage.of(records, result.getTotal(), query.getPageNum(), query.getPageSize());
        } finally {
            record("search", ok, start);
        }
    }

    /**
     * 打分召回（S128）。与 {@link #search} 共用索引名解析、条件树与实体反序列化，
     * 差别只有两点：① 先确保字段的<b>全文映射</b>就位（{@code createFullTextIndexIfAbsent}）；
     * ② 结果不分页、按分数降序。
     */
    @Override
    public <T> SearchScoredPage<T> searchScored(LambdaSearchQuery<T> query) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            requireQuery(query);
            String indexName = resolveIndex(query);
            SearchProvider provider = factory.get();
            // 全文通道必须有一份可被 BM25 打分的字段类型：
            // 确定性索引把字符串一律落成 keyword（整值相等），而 keyword 上没有词频/TF-IDF 概念。
            // 默认实现就是 createIndexIfAbsent（simple 无 mapping 概念），只有搜索引擎实现才需要覆写。
            provider.createFullTextIndexIfAbsent(indexName, query.getFullTextFields());

            ScoredSearchRequest request = ScoredSearchRequest.of(indexName, query.getKeyword(),
                    query.getFullTextFields(), query.getCriteria(), query.getPageSize(),
                    query.getMinScore(), query.getCandidateWindow());
            SearchResult result = provider.searchScored(request);

            List<ScoredRecord<T>> records = new ArrayList<>();
            for (SearchHit hit : result.getHits()) {
                T entity = SearchEntityMapper.fromMap(hit.getSource(), query.getEntityType());
                if (entity != null) {
                    records.add(ScoredRecord.of(entity, hit.getScore()));
                }
            }
            ok = true;
            return SearchScoredPage.of(records, result.getTotal());
        } finally {
            record("searchScored", ok, start);
        }
    }

    /**
     * 提前建全文映射（{@link #index} 不知道哪些字段要打 BM25，故由调用方在写数据前显式声明）。
     * 幂等：simple 侧等于建桶；ES 侧索引不存在则建、已存在则追加 text 字段。
     */
    @Override
    public <T> void createFullTextIndexIfAbsent(Class<T> entityType, String... fields) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            Objects.requireNonNull(entityType, "entityType 不能为 null");
            String indexName = SearchIndexNameResolver.resolve(entityType);
            factory.get().createFullTextIndexIfAbsent(indexName,
                    fields == null ? List.of() : Arrays.asList(fields));
            ok = true;
        } finally {
            record("createFullTextIndexIfAbsent", ok, start);
        }
    }

    @Override
    public <T> long count(LambdaSearchQuery<T> query) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            requireQuery(query);
            String indexName = resolveIndex(query);
            factory.get().createIndexIfAbsent(indexName);
            long total = factory.get().count(SearchRequest.of(indexName, query.getCriteria(),
                    query.getOrders(), query.getPageNum(), query.getPageSize()));
            ok = true;
            return total;
        } finally {
            record("count", ok, start);
        }
    }

    @Override
    public <T> void index(T entity) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            factory.get().index(toDocument(entity));
            ok = true;
        } finally {
            record("index", ok, start);
        }
    }

    @Override
    public <T> void indexBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            // 空批不是一次检索调用，不埋点（否则「什么都没做」也会被计成一次操作）
            return;
        }
        long start = System.nanoTime();
        boolean ok = false;
        try {
            List<SearchDocument> docs = new ArrayList<>(entities.size());
            for (T entity : entities) {
                docs.add(toDocument(entity));
            }
            factory.get().indexBatch(docs);
            ok = true;
        } finally {
            record("indexBatch", ok, start);
        }
    }

    @Override
    public void delete(String indexName, String id) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            SearchIndexNameResolver.requireValid(indexName);
            factory.get().delete(SearchIndexNameResolver.applyPrefix(indexName, properties.getIndexPrefix()), id);
            ok = true;
        } finally {
            record("delete", ok, start);
        }
    }

    @Override
    public SearchProviderType type() {
        return factory.effectiveType();
    }

    // ==================== 内部 ====================

    private <T> SearchDocument toDocument(T entity) {
        String indexName = SearchEntityMapper.resolveIndexName(entity.getClass(), properties.getIndexPrefix());
        return SearchDocument.of(indexName, SearchEntityMapper.resolveDocId(entity),
                SearchEntityMapper.toMap(entity));
    }

    private <T> String resolveIndex(LambdaSearchQuery<T> query) {
        String explicit = query.getIndexName();
        String raw = (explicit == null || explicit.isBlank())
                ? SearchIndexNameResolver.resolve(query.getEntityType())
                : explicit.trim();
        return SearchIndexNameResolver.applyPrefix(raw, properties.getIndexPrefix());
    }

    private <T> void requireQuery(LambdaSearchQuery<T> query) {
        if (query == null || query.getEntityType() == null) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "查询对象不能为空");
        }
    }

    /** 埋点：{@code outcome} 由 finally 里的 ok 决定，抛异常即记 failure 且异常继续上抛（不改变既有语义） */
    private void record(String operation, boolean ok, long startNanos) {
        SearchProviderType type = factory.effectiveType();
        businessMetrics.recordSearch(type == null ? "unknown" : type.name(),
                operation, ok, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}

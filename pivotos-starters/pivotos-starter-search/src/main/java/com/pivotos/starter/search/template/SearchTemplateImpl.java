package com.pivotos.starter.search.template;

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
 * @author PivotOS Team
 * @since 2.16.0
 */
public class SearchTemplateImpl implements SearchTemplate {

    private final SearchProviderFactory factory;
    private final SearchProperties properties;

    public SearchTemplateImpl(SearchProviderFactory factory, SearchProperties properties) {
        this.factory = factory;
        this.properties = properties == null ? new SearchProperties() : properties;
    }

    @Override
    public <T> SearchPage<T> search(LambdaSearchQuery<T> query) {
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
        return SearchPage.of(records, result.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 打分召回（S128）。与 {@link #search} 共用索引名解析、条件树与实体反序列化，
     * 差别只有两点：① 先确保字段的<b>全文映射</b>就位（{@code createFullTextIndexIfAbsent}）；
     * ② 结果不分页、按分数降序。
     */
    @Override
    public <T> SearchScoredPage<T> searchScored(LambdaSearchQuery<T> query) {
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
        return SearchScoredPage.of(records, result.getTotal());
    }

    /**
     * 提前建全文映射（{@link #index} 不知道哪些字段要打 BM25，故由调用方在写数据前显式声明）。
     * 幂等：simple 侧等于建桶；ES 侧索引不存在则建、已存在则追加 text 字段。
     */
    @Override
    public <T> void createFullTextIndexIfAbsent(Class<T> entityType, String... fields) {
        Objects.requireNonNull(entityType, "entityType 不能为 null");
        String indexName = SearchIndexNameResolver.resolve(entityType);
        factory.get().createFullTextIndexIfAbsent(indexName,
                fields == null ? List.of() : Arrays.asList(fields));
    }

    @Override
    public <T> long count(LambdaSearchQuery<T> query) {
        requireQuery(query);
        String indexName = resolveIndex(query);
        factory.get().createIndexIfAbsent(indexName);
        return factory.get().count(SearchRequest.of(indexName, query.getCriteria(),
                query.getOrders(), query.getPageNum(), query.getPageSize()));
    }

    @Override
    public <T> void index(T entity) {
        factory.get().index(toDocument(entity));
    }

    @Override
    public <T> void indexBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<SearchDocument> docs = new ArrayList<>(entities.size());
        for (T entity : entities) {
            docs.add(toDocument(entity));
        }
        factory.get().indexBatch(docs);
    }

    @Override
    public void delete(String indexName, String id) {
        SearchIndexNameResolver.requireValid(indexName);
        factory.get().delete(SearchIndexNameResolver.applyPrefix(indexName, properties.getIndexPrefix()), id);
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
}

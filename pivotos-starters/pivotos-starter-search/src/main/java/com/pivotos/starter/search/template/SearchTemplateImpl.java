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
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.starter.search.route.SearchProviderFactory;

import java.util.ArrayList;
import java.util.List;

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

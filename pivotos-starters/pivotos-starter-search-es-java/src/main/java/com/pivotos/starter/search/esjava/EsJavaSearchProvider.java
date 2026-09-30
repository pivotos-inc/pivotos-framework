package com.pivotos.starter.search.esjava;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TrackHits;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.query.SearchOrder;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.esjava.query.EsJavaQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * elasticsearch-java 实现（官方新客户端，面向 ES 8.x/9.x）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class EsJavaSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(EsJavaSearchProvider.class);

    private final ElasticsearchClient client;

    public EsJavaSearchProvider(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public SearchProviderType type() {
        return SearchProviderType.ES_JAVA;
    }

    @Override
    public void index(SearchDocument document) {
        requireDocument(document);
        execute(() -> client.index(new IndexRequest.Builder<Map>()
                .index(document.getIndexName())
                .id(document.getId())
                .document(document.getSource())
                .build()));
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
        execute(() -> client.delete(new DeleteRequest.Builder().index(indexName).id(id).build()));
    }

    @Override
    public SearchResult search(com.pivotos.starter.search.api.document.SearchRequest request) {
        Query query = EsJavaQueryBuilder.build(request.getCriteria());
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(request.getIndexName())
                .query(query)
                .from(request.offset())
                .size(Math.max(request.getPageSize(), 1))
                .trackTotalHits(new TrackHits.Builder().enabled(true).build());
        applyOrders(builder, request.getOrders());

        SearchResponse<Map> response = execute(() -> client.search(builder.build(), Map.class));
        List<SearchHit> hits = new ArrayList<>();
        if (response != null && response.hits() != null) {
            for (Hit<Map> hit : response.hits().hits()) {
                hits.add(SearchHit.of(hit.id(), hit.score() == null ? 0D : hit.score(),
                        hit.source() == null ? Map.of() : hit.source()));
            }
        }
        long total = response != null && response.hits() != null && response.hits().total() != null
                ? response.hits().total().value()
                : hits.size();
        return SearchResult.of(hits, total);
    }

    @Override
    public long count(com.pivotos.starter.search.api.document.SearchRequest request) {
        Query query = EsJavaQueryBuilder.build(request.getCriteria());
        Long value = execute(() -> client.count(new CountRequest.Builder()
                .index(request.getIndexName()).query(query).build()).count());
        return value == null ? 0L : value;
    }

    @Override
    public boolean existsIndex(String indexName) {
        Boolean exists = execute(() -> client.indices()
                .exists(new ExistsRequest.Builder().index(indexName).build()).value());
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void createIndexIfAbsent(String indexName) {
        try {
            execute(() -> client.indices().create(new CreateIndexRequest.Builder().index(indexName).build()));
        } catch (SearchException e) {
            // 索引已存在时 ES 返回 400 resource_already_exists_exception，属预期，降级为 debug
            log.debug("[PivotOS][search] es-java 索引 {} 创建跳过（可能已存在）：{}", indexName, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private void applyOrders(SearchRequest.Builder builder, List<SearchOrder> orders) {
        if (orders == null) {
            return;
        }
        for (SearchOrder order : orders) {
            builder.sort(new SortOptions.Builder().field(new FieldSort.Builder()
                    .field(order.getField())
                    .order(order.isAsc() ? SortOrder.Asc : SortOrder.Desc)
                    .build()).build());
        }
    }

    /**
     * 统一包装客户端 IO 异常：底层是 IOException / ElasticsearchException，一律转成带业务码的
     * {@link SearchException}，避免把受检异常泄漏到业务层
     */
    private <T> T execute(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(SearchErrorCode.SEARCH_EXECUTE_FAILED, e.getMessage(), e);
        }
    }

    private void requireDocument(SearchDocument document) {
        if (document == null || document.getIndexName() == null || document.getId() == null) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "搜索文档不完整：indexName 与 id 均不可为空");
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}

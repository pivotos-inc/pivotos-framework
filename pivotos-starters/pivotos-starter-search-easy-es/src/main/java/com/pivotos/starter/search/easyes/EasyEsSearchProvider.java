package com.pivotos.starter.search.easyes;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.easyes.query.EasyEsQueryBuilder;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.dromara.easyes.core.kernel.BaseEsMapperImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Easy-ES 实现（easy-es-core 3.0.2，面向 ES 7.17）。
 * <p>分工：
 * <ul>
 *   <li>条件 → Easy-ES 原生 {@link LambdaEsQueryWrapper}（本实现的核心价值：Easy-ES 的 Lambda DSL）；</li>
 *   <li>请求装配 → {@code BaseEsMapperImpl#getSearchBuilder}（Easy-ES 官方的 DSL 编译入口）；</li>
 *   <li>网络执行与索引管理 → 底层官方客户端（Easy-ES 内部同样是它）。</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class EasyEsSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(EasyEsSearchProvider.class);

    private final BaseEsMapperImpl<HashMap> mapper;
    private final ElasticsearchClient client;

    public EasyEsSearchProvider(BaseEsMapperImpl<HashMap> mapper, ElasticsearchClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public SearchProviderType type() {
        return SearchProviderType.EASY_ES;
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
        co.elastic.clients.elasticsearch.core.SearchRequest esRequest = buildSearchRequest(request);
        SearchResponse<Map> response = execute(() -> client.search(esRequest, Map.class));
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
        co.elastic.clients.elasticsearch.core.SearchRequest esRequest = buildSearchRequest(request);
        Long value = execute(() -> client.count(new CountRequest.Builder()
                .index(request.getIndexName())
                .query(esRequest.query())
                .build()).count());
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
            log.debug("[PivotOS][search] easy-es 索引 {} 创建跳过（可能已存在）：{}", indexName, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * 条件树 → Easy-ES wrapper → Easy-ES 编译出的 SearchRequest。
     * 这一步是纯构建（不发请求），因此可离线单测——本实现对 Easy-ES 的验证面就落在这里。
     */
    private co.elastic.clients.elasticsearch.core.SearchRequest buildSearchRequest(
            com.pivotos.starter.search.api.document.SearchRequest request) {
        LambdaEsQueryWrapper<HashMap> wrapper = EasyEsQueryBuilder.build(request.getCriteria(), request.getOrders());
        // 索引名显式覆盖：不能用 setCurrentActiveIndex（它依赖实体元信息里的 @IndexName 注解，
        // 而我们的实体载体是无注解的 HashMap）
        return mapper.getSearchBuilder(wrapper)
                .index(request.getIndexName())
                .from(request.offset())
                .size(Math.max(request.getPageSize(), 1))
                .build();
    }

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

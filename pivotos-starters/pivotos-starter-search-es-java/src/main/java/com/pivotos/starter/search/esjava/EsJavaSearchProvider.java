package com.pivotos.starter.search.esjava;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
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
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.ElasticsearchTransportBase;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.NamedValue;
import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.query.SearchOrder;
import com.pivotos.starter.search.api.spi.SearchProvider;
import com.pivotos.starter.search.esjava.query.EsJavaQueryBuilder;
import com.pivotos.starter.search.esjava.support.EsServerVersion;
import com.pivotos.starter.search.esjava.support.EsServerVersionProbe;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * elasticsearch-java 实现（官方客户端 8.19.18，<b>一套实现覆盖 ES 7.17 / 8.x / 9.x</b>）。
 * <p>多版本靠两件事达成，<b>不需要 shade 双客户端</b>：
 * <ol>
 *   <li>wire 形态：{@code compatibility-mode=true} 时发 {@code compatible-with=7} 头（连 7.17），
 *       否则发 {@code compatible-with=8}（连 8.x / 9.x）；</li>
 *   <li>建索引走原始 JSON：绕开「类型化 API 把 match_mapping_type 序列化成数组，7.17 拒绝」这一版本差异。</li>
 * </ol>
 * 启动期还会探测服务端版本，不在支持区间则 {@link #isAvailable()} 返回 false，由路由工厂回落 simple。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class EsJavaSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(EsJavaSearchProvider.class);

    /**
     * 建索引的原始 mapping。
     * <p><b>为什么不用类型化 API</b>：es-java 8.x 的 {@code DynamicTemplate#matchMappingType} 是
     * {@code List<String>}，序列化成 {@code ["string"]}；ES 9.x 接受数组，而 <b>ES 7.17 只接受字符串</b>
     * ——实测 7.17.28 直接报 {@code mapper_parsing_exception: No field type matched on [[string]]}，
     * 索引建不出来且后续写入全部动态映射（text）→ 中文 EQ / 时间区间静默失效（S122 缺陷①复现）。
     * 字符串形态 {@code "string"} 在 7.17 / 8.x / 9.x 三个版本上都被接受，故统一走原始 JSON：
     * 一份代码覆盖三个大版本，不必按版本分叉。
     */
    private static final String KEYWORD_DYNAMIC_TEMPLATE_MAPPING = "{\"mappings\":{\"dynamic_templates\":["
            + "{\"strings_as_keywords\":{\"match_mapping_type\":\"string\","
            + "\"mapping\":{\"type\":\"keyword\",\"ignore_above\":8191}}}]}}";

    private final ElasticsearchClient client;

    /** 写入后是否 wait_for 刷新（ES 近实时权衡，见 SearchProperties.EsJava#refreshOnWrite） */
    private final boolean refreshOnWrite;

    /** 启动期探测到的服务端版本（探测失败为 UNKNOWN） */
    private final EsServerVersion serverVersion;

    /** 启动期自检结论：不可用则路由工厂回落到 simple（见 SearchProvider#isAvailable） */
    private final boolean available;

    public EsJavaSearchProvider(ElasticsearchClient client) {
        this(client, false);
    }

    public EsJavaSearchProvider(ElasticsearchClient client, boolean refreshOnWrite) {
        this.client = client;
        this.refreshOnWrite = refreshOnWrite;
        this.serverVersion = EsServerVersionProbe.probe(client);
        this.available = checkAvailability();
    }

    @Override
    public SearchProviderType type() {
        return SearchProviderType.ES_JAVA;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /** 启动期探测到的服务端版本（运维核对/自诊断用） */
    public EsServerVersion serverVersion() {
        return serverVersion;
    }

    /**
     * 启动期可用性自检：<b>只打日志、绝不抛异常</b>。
     * 三种不可用形态：① 探测不到版本（ES 不可达 / 兼容头被拒）；② 版本不在支持区间；
     * ③ 开了兼容模式却连着 8.x/9.x 服务端（ES 9.5.3 实测拒绝 compatible-with=7）。
     */
    private boolean checkAvailability() {
        if (serverVersion.isUnknown()) {
            log.warn("[PivotOS][search] es-java 不可用：未能探测到 ES 服务端版本"
                    + "（ES 不可达、认证失败或兼容头被服务端拒绝）；已回落 simple 内存实现。");
            return false;
        }
        if (!serverVersion.isSupported()) {
            log.warn("[PivotOS][search] es-java 不可用：服务端版本 {} 不在支持区间 {}；"
                            + "已回落 simple 内存实现。请升级 ES 或改用 easy-es 实现（ES 7.17）。",
                    serverVersion.raw(), EsServerVersion.supportRangeText());
            return false;
        }
        if (isCompatibilityMode() && !serverVersion.needsCompatibilityHeader()) {
            log.warn("[PivotOS][search] es-java 不可用：compatibility-mode=true 会发送 compatible-with=7，"
                            + "而服务端是 {}（8.x/9.x 会拒绝该兼容头）；"
                            + "请置 pivotos.search.es-java.compatibility-mode=false。已回落 simple 内存实现。",
                    serverVersion.raw());
            return false;
        }
        log.info("[PivotOS][search] es-java 就绪：服务端 {}，兼容模式={}（支持区间 {}）",
                serverVersion.raw(), isCompatibilityMode(), EsServerVersion.supportRangeText());
        return true;
    }

    @Override
    public void index(SearchDocument document) {
        requireDocument(document);
        execute(() -> client.index(new IndexRequest.Builder<Map>()
                .index(document.getIndexName())
                .id(document.getId())
                .document(document.getSource())
                // ES 默认近实时（1s refresh）；refreshOnWrite=true 时改为 wait_for，写后立即可检索
                .refresh(refreshOnWrite ? Refresh.WaitFor : Refresh.False)
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
        // 与 index() 同口径：refreshOnWrite=true 时删除也 wait_for，否则「刚删就能查到」是 ES 近实时的正常表现
        execute(() -> client.delete(new DeleteRequest.Builder()
                .index(indexName)
                .id(id)
                .refresh(refreshOnWrite ? Refresh.WaitFor : Refresh.False)
                .build()));
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

    /**
     * 建索引（不存在时）。<b>必须带 mapping</b>，不能交给动态映射：
     * ES 动态映射会把字符串落成 {@code text}（standard 分词），而本抽象的条件语义是
     * <b>确定性过滤</b>（eq / like / 时间区间），打在分词后的 text 字段上会静默失效——
     * 中文场景实测：{@code term(module:"用户管理")} 命中 0、{@code wildcard(module:"*用户*")} 命中 0
     * （分词成单字），而同样条件打 {@code keyword} 命中正常。
     * 故这里用 dynamic_templates 把所有字符串字段落成 keyword（与 simple 实现的「整值比较」语义一致）。
     * <p>时间以定长字符串 {@code yyyy-MM-dd HH:mm:ss} 存放，keyword 的字典序 = 时间序，区间与排序均正确。
     */
    @Override
    public void createIndexIfAbsent(String indexName) {
        RestClient lowLevel = lowLevelClient();
        if (lowLevel == null) {
            // 拿不到低层客户端（Client 不是基于 RestClientTransport 构建的，如测试替身）→ 退回类型化 API
            createIndexTyped(indexName);
            return;
        }
        String mediaType = mediaType();
        try {
            Request request = new Request("PUT", "/" + indexName);
            request.setEntity(new StringEntity(KEYWORD_DYNAMIC_TEMPLATE_MAPPING, ContentType.parse(mediaType)));
            // Accept 与 Content-Type 必须同时带同一 compatible-with，否则服务端报 media_type_header_exception
            RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
            options.addHeader("Accept", mediaType);
            options.addHeader("Content-Type", mediaType);
            request.setOptions(options.build());
            Response response = lowLevel.performRequest(request);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 300) {
                log.warn("[PivotOS][search] es-java 索引 {} 创建返回 {}（服务端 {}）", indexName, status, serverVersion.raw());
            }
        } catch (Exception e) {
            // 索引已存在时 ES 返回 400 resource_already_exists_exception，属预期，降级为 debug
            log.debug("[PivotOS][search] es-java 索引 {} 创建跳过（可能已存在）：{}", indexName, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /**
     * 类型化建索引（<b>仅兜底路径</b>）。
     * 注意：它产出的 {@code match_mapping_type} 是数组形态，ES 7.17 会拒绝，故正常链路走原始 JSON。
     */
    private void createIndexTyped(String indexName) {
        TypeMapping mapping = new TypeMapping.Builder()
                .dynamicTemplates(NamedValue.of("strings_as_keywords", new DynamicTemplate.Builder()
                        .matchMappingType("string")
                        .mapping(new Property.Builder().keyword(new KeywordProperty.Builder()
                                .ignoreAbove(8191)
                                .build())
                                .build())
                        .build()))
                .build();
        try {
            execute(() -> client.indices().create(new CreateIndexRequest.Builder()
                    .index(indexName)
                    .mappings(mapping)
                    .build()));
        } catch (SearchException e) {
            log.debug("[PivotOS][search] es-java 索引 {} 创建跳过（可能已存在）：{}", indexName, e.getMessage());
        }
    }

    /** 低层客户端（拿不到时为 null） */
    private RestClient lowLevelClient() {
        ElasticsearchTransport transport = client == null ? null : client._transport();
        return transport instanceof RestClientTransport rest ? rest.restClient() : null;
    }

    /** 当前客户端实际使用的媒体类型（Accept 头），兼容模式下为 compatible-with=7 */
    private String mediaType() {
        if (client == null || client._transport() == null) {
            return ElasticsearchTransportBase.JSON_CONTENT_TYPE;
        }
        for (Map.Entry<String, String> header : client._transport().options().headers()) {
            if ("Accept".equalsIgnoreCase(header.getKey())) {
                return header.getValue();
            }
        }
        return ElasticsearchTransportBase.JSON_CONTENT_TYPE;
    }

    /** 该客户端是否工作在兼容模式（Accept 头带 compatible-with=7） */
    private boolean isCompatibilityMode() {
        return mediaType().contains("compatible-with=7");
    }

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

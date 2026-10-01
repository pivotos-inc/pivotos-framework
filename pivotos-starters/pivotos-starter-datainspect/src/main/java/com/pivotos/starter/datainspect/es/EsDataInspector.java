package com.pivotos.starter.datainspect.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import com.pivotos.starter.datainspect.api.model.ColumnItem;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import com.pivotos.starter.datainspect.security.ColumnMasker;
import com.pivotos.starter.datainspect.support.InspectValues;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.esjava.EsJavaSearchProvider;
import com.pivotos.starter.search.esjava.support.EsRestSupport;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Elasticsearch 数据监控实现（<b>纯只读</b>）：索引列举 + 文档预览 + 索引统计。
 *
 * <p><b>可用条件（设计 §11-5）</b>：仅在 {@code pivotos.search.type=es-java} <b>且客户端就绪</b>时可用——
 * 复用 es-java 既有客户端与其鉴权/兼容头，不另建连接、不另配一套地址。
 * type=simple / easy-es / 未引入实现 / 版本不在支持区间 / 连接不可达 → 一律
 * {@code 200 + code=0 + available=false + reason}，<b>绝不抛异常、绝不 500</b>。
 *
 * <p><b>为什么只走两个端点</b>：{@code _cat/indices}（元数据）与 {@code _search}（只读检索）。
 * 写端点（{@code _bulk} / {@code _delete_by_query} / {@code _update_by_query} / {@code _close} /
 * {@code _refresh} …）<b>实现层根本不提供入口</b>；请求体由本类固定拼装（强制注入 {@code size} 与
 * {@code track_total_hits:true}），不接受用户语句。
 *
 * <p><b>为什么没有 QUERY 能力</b>：自由 DSL 无法做等价的语句级闸门（body 是任意 JSON，
 * 端点由 path 决定，一个 {@code script} 字段就能把只读查询变成副作用），因此按设计 §5 的取舍口径
 * <b>只暴露结构化浏览</b>，不开放自由 DSL 入口（ES {@code _sql} 统一入口仍列 P3）。
 *
 * <p>跨版本：全部走低层 {@link RestClient} 发原始 JSON（{@link EsRestSupport}），
 * 兼容头 Accept + Content-Type 必须成对（S127 教训：只带一个是 400）。
 *
 * <p>租户语义（显式声明）：索引是平台级存储，<b>索引清单天然跨租户</b>；文档预览不做租户改写
 * （ES 文档无统一 tenant_id 字段约定），靠 {@code monitor:data:list/preview} 权限 + 敏感字段脱敏收口。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class EsDataInspector implements DataSourceInspector {

    private static final Logger log = LoggerFactory.getLogger(EsDataInspector.class);

    /** 无 QUERY：不开放自由 DSL */
    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.LIST_SCHEMAS, Capability.LIST_TABLES, Capability.PREVIEW, Capability.STATS);

    /** ES 的「库」是固定分组：索引分组（设计 §3.4） */
    public static final String SCHEMA_INDICES = "indices";

    private static final String CAT_INDICES = "/_cat/indices?format=json&bytes=b"
            + "&h=index,health,status,docs.count,store.size,pri,rep";

    /** 索引名：只放行安全字符，且禁止 {@code .} 开头（系统内建索引）与通配符/路径分隔符 */
    private static final Pattern INDEX_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 单条预览最多展示的字段列数（防宽表把表格撑爆） */
    private static final int MAX_COLUMNS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ElasticsearchClient client;
    private final SearchProperties searchProperties;
    private final EsJavaSearchProvider provider;
    private final DataInspectProperties properties;
    private final ColumnMasker masker;

    /** 首次探测结论缓存（与 ES 监控同口径：恢复需重启） */
    private volatile Boolean available;
    private volatile RejectReason unavailableReason;
    private volatile String unavailableDetail;

    public EsDataInspector(ElasticsearchClient client, SearchProperties searchProperties,
                           EsJavaSearchProvider provider, DataInspectProperties properties) {
        this.client = client;
        this.searchProperties = searchProperties;
        this.provider = provider;
        this.properties = properties == null ? new DataInspectProperties() : properties;
        this.masker = new ColumnMasker(this.properties.getMaskColumnRegex());
    }

    // ---------- SPI 基础 ----------

    @Override
    public DataSourceType type() {
        return DataSourceType.ES;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available == null) {
                probe();
            }
            return Boolean.TRUE.equals(available);
        }
    }

    @Override
    public ComponentSnapshot snapshot() {
        if (!isAvailable()) {
            return ComponentSnapshot.unavailable(DataSourceType.ES, unavailableReason, unavailableDetail);
        }
        DataInspectProperties.Es es = properties.getEs();
        return ComponentSnapshot.of(DataSourceType.ES, true, null,
                "服务端 " + serverVersionText() + " · 单页最多 " + es.getMaxRows() + " 文档 / 索引清单最多 "
                        + es.getMaxItems() + " / 字段截断 " + es.getValueTruncateBytes() + " 字符",
                CAPABILITIES);
    }

    /**
     * 可用性判定（四态，全部降级不抛异常）：
     * <ol>
     *   <li>客户端未装配 → IMPL_MISSING（含「未引入实现模块」与「type 非 es-java」两种明细）；</li>
     *   <li>配置的 type 不是 es-java → UNSUPPORTED（这是最常见的「配了 ES 但走 simple」形态）；</li>
     *   <li>Provider 自检未通过 / 版本不在支持区间 → UNREACHABLE 或 UNSUPPORTED；</li>
     *   <li>探测不到服务端 → UNREACHABLE。</li>
     * </ol>
     */
    private void probe() {
        String configured = configuredType();
        if (client == null) {
            available = false;
            unavailableReason = RejectReason.IMPL_MISSING;
            unavailableDetail = "未装配 ElasticsearchClient（pivotos.search.type=" + configured
                    + "；ES 数据监控仅在 es-java 实现下可用）";
            log.warn("[PivotOS][datainspect] {}", unavailableDetail);
            return;
        }
        if (!SearchProviderType.ES_JAVA.getCode().equals(configured)) {
            available = false;
            unavailableReason = RejectReason.UNSUPPORTED;
            unavailableDetail = "pivotos.search.type=" + configured + "（仅 es-java 实现提供 ES 数据监控）";
            log.warn("[PivotOS][datainspect] ES 组件不可用：{}", unavailableDetail);
            return;
        }
        if (provider != null && !provider.isAvailable()) {
            available = false;
            unavailableReason = RejectReason.UNREACHABLE;
            unavailableDetail = "es-java 实现自检未通过（服务端 " + provider.serverVersion().raw() + "）";
            log.warn("[PivotOS][datainspect] ES 组件不可用：{}", unavailableDetail);
            return;
        }
        EsServerVersion version = EsServerVersionProbe.probe(client);
        if (version.isUnknown()) {
            available = false;
            unavailableReason = RejectReason.UNREACHABLE;
            unavailableDetail = "ES 服务端不可达或版本探测失败";
            log.warn("[PivotOS][datainspect] ES 组件不可用：{}", unavailableDetail);
            return;
        }
        if (!version.isSupported()) {
            available = false;
            unavailableReason = RejectReason.UNSUPPORTED;
            unavailableDetail = "服务端版本 " + version.raw() + " 不在支持区间（" + EsServerVersion.supportRangeText() + "）";
            log.warn("[PivotOS][datainspect] ES 组件不可用：{}", unavailableDetail);
            return;
        }
        available = true;
        unavailableReason = null;
        unavailableDetail = null;
    }

    private String configuredType() {
        return searchProperties == null ? null : searchProperties.getType();
    }

    private String serverVersionText() {
        if (provider != null) {
            return provider.serverVersion().raw();
        }
        return EsServerVersionProbe.probe(client).raw();
    }

    // ---------- 元数据 ----------

    @Override
    public List<SchemaItem> listSchemas() {
        List<EsInspectJson.IndexRow> indices = fetchIndices();
        if (!isAvailable()) {
            return List.of();
        }
        return List.of(SchemaItem.builder()
                .name(SCHEMA_INDICES)
                .label("索引（indices）")
                .itemCount(indices.size())
                .build());
    }

    @Override
    public List<TableItem> listTables(String schema) {
        List<EsInspectJson.IndexRow> indices = fetchIndices();
        if (!isAvailable()) {
            return List.of();
        }
        List<TableItem> items = new ArrayList<>();
        int maxItems = Math.max(1, properties.getEs().getMaxItems());
        for (EsInspectJson.IndexRow row : indices) {
            if (items.size() >= maxItems) {
                log.warn("[PivotOS][datainspect] ES 索引清单触及上限 {}，已截断", maxItems);
                break;
            }
            items.add(TableItem.builder()
                    .schema(SCHEMA_INDICES)
                    .name(row.index())
                    .type("index")
                    .comment(joinHealth(row))
                    .rowCount(row.docs())
                    .ttl(-1)
                    .build());
        }
        return items;
    }

    @Override
    public StatsItem stats(String schema, String table) {
        StatsItem.StatsItemBuilder builder = StatsItem.builder().schema(schema).table(table);
        if (!isAvailable() || !validIndexName(table)) {
            return builder.rowCount(-1).sizeBytes(-1).build();
        }
        List<EsInspectJson.IndexRow> indices;
        try {
            indices = EsInspectJson.parseIndices(requestJson(CAT_INDICES));
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] ES 统计失败（index={}）：{}", table, rootMessage(e));
            return builder.rowCount(-1).sizeBytes(-1).build();
        }
        for (EsInspectJson.IndexRow row : indices) {
            if (row.index().equals(table)) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("health", nullToEmpty(row.health()));
                extra.put("status", nullToEmpty(row.status()));
                extra.put("pri", nullToEmpty(row.pri()));
                extra.put("rep", nullToEmpty(row.rep()));
                return builder.rowCount(row.docs()).sizeBytes(row.storeBytes())
                        .engine("index").extra(extra).build();
            }
        }
        return builder.rowCount(-1).sizeBytes(-1).build();
    }

    // ---------- 数据 ----------

    @Override
    public QueryResult preview(PreviewRequest request) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) {
            return QueryResult.unavailable(unavailableReason == null ? RejectReason.UNREACHABLE : unavailableReason,
                    unavailableDetail);
        }
        String index = request == null ? null : request.getTable();
        if (!validIndexName(index)) {
            return QueryResult.unavailable(RejectReason.FORBIDDEN, "索引名不合法：" + index);
        }
        int pageSize = request.getPageSize() <= 0 ? 20 : request.getPageSize();
        int pageNum = request.getPageNum() <= 0 ? 1 : request.getPageNum();
        int size = Math.min(Math.min(pageSize, Math.max(1, properties.getEs().getMaxRows())), hardMaxRows());
        int from = Math.max(0, (pageNum - 1) * size);

        // 请求体由本类固定拼装：强制注入 size 与 track_total_hits，不接受任何用户语句
        String body = "{\"from\":" + from + ",\"size\":" + size + ",\"track_total_hits\":true,"
                + "\"query\":{\"match_all\":{}}}";
        EsInspectJson.SearchPage page;
        try {
            page = EsInspectJson.parseSearch(requestJsonWithBody("/" + index + "/_search", body));
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] ES 预览失败（index={}）：{}", index, rootMessage(e));
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }

        List<String> warnings = new ArrayList<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        for (Map<String, Object> doc : page.docs()) {
            for (String field : doc.keySet()) {
                if (fieldNames.size() < MAX_COLUMNS) {
                    fieldNames.add(field);
                }
            }
        }
        List<ColumnItem> columns = new ArrayList<>();
        List<Boolean> maskedFlags = new ArrayList<>();
        for (String field : fieldNames) {
            boolean masked = properties.isMaskEnabled() && masker.isSensitive(field);
            maskedFlags.add(masked);
            columns.add(ColumnItem.builder().name(field).type("text").masked(masked).build());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int maskedCells = 0;
        boolean valueTruncated = false;
        for (Map<String, Object> doc : page.docs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            int i = 0;
            for (String field : fieldNames) {
                Object value = doc.get(field);
                boolean masked = i < maskedFlags.size() && maskedFlags.get(i);
                i++;
                if (masked) {
                    value = masker.mask(field, value);
                    maskedCells++;
                }
                value = InspectValues.normalize(value, properties.getEs().getValueTruncateBytes());
                if (InspectValues.isTruncated(value)) {
                    valueTruncated = true;
                }
                row.put(field, value);
            }
            rows.add(row);
        }
        if (maskedCells > 0) {
            warnings.add("已脱敏 " + maskedCells + " 个单元格（命中敏感字段名规则）");
        }
        if (valueTruncated) {
            warnings.add("字段值已截断至 " + properties.getEs().getValueTruncateBytes() + " 字符");
        }
        boolean truncated = valueTruncated || (page.total() > 0 && from + rows.size() < page.total());
        if (truncated && rows.size() >= size) {
            warnings.add("结果已截断，单页最多 " + size + " 条文档");
        }

        long duration = System.currentTimeMillis() - start;
        if (duration > properties.getSlowThresholdMs()) {
            log.warn("[PivotOS][datainspect] 慢语句：{}ms > {}ms | {} /_search", duration,
                    properties.getSlowThresholdMs(), index);
            warnings.add("慢语句：" + duration + "ms（阈值 " + properties.getSlowThresholdMs() + "ms）");
        }
        return QueryResult.builder()
                .columns(columns)
                .rows(rows)
                .total(page.total())
                .truncated(truncated)
                .durationMs(duration)
                .warnings(warnings)
                .available(true)
                .build();
    }

    @Override
    public QueryResult query(QueryRequest request) {
        // 无 QUERY 能力：即便被绕过前端直接调用，也只给结构化浏览
        return QueryResult.unavailable(RejectReason.UNSUPPORTED,
                "ES 组件只提供结构化浏览（_cat/indices + _search），不开放自由 DSL 入口");
    }

    // ---------- 端点白名单内的请求 ----------

    /** 拉取索引清单（旁路：失败只让本次调用降级，不改可用性结论） */
    private List<EsInspectJson.IndexRow> fetchIndices() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            return EsInspectJson.parseIndices(requestJson(CAT_INDICES));
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] ES 索引清单采集失败：{}", rootMessage(e));
            return List.of();
        }
    }

    /** 原始 JSON GET：Accept 与 Content-Type 必须成对带同一媒体类型（S127 教训） */
    private JsonNode requestJson(String endpoint) throws Exception {
        RestClient rest = EsRestSupport.lowLevelClient(client);
        if (rest == null) {
            throw new IllegalStateException("无法获取低层 HTTP 客户端");
        }
        return execute(rest, "GET", endpoint, null);
    }

    private JsonNode requestJsonWithBody(String endpoint, String body) throws Exception {
        RestClient rest = EsRestSupport.lowLevelClient(client);
        if (rest == null) {
            throw new IllegalStateException("无法获取低层 HTTP 客户端");
        }
        return execute(rest, "POST", endpoint, body);
    }

    private JsonNode execute(RestClient rest, String method, String endpoint, String body) throws Exception {
        Request request = new Request(method, endpoint);
        String mediaType = EsRestSupport.mediaType(client);
        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.addHeader("Accept", mediaType);
        options.addHeader("Content-Type", mediaType);
        request.setOptions(options.build());
        if (body != null) {
            // 必须用 parse 而不是 create(mimeType, charset)：后者会校验「MIME type 不得含参数」，
            // 而兼容头是 application/vnd.elasticsearch+json; compatible-with=7 —— 带参数，create 会直接抛
            // 「MIME type may not contain reserved characters」（S130-P2 实测）
            request.setEntity(new StringEntity(body, ContentType.parse(mediaType)));
        }
        Response response = rest.performRequest(request);
        int status = response.getStatusLine().getStatusCode();
        if (status >= 300) {
            throw new IllegalStateException("ES " + endpoint + " 返回 HTTP " + status);
        }
        try (InputStream in = response.getEntity().getContent()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? null : MAPPER.readTree(text);
        }
    }

    // ---------- 工具 ----------

    /** 索引名白名单：禁止通配、逗号、路径分隔符与系统内建索引（防 URL 注入到别的端点） */
    public static boolean validIndexName(String name) {
        if (name == null || name.isBlank() || name.startsWith(".")) {
            return false;
        }
        return INDEX_NAME.matcher(name).matches();
    }

    private int hardMaxRows() {
        return properties.getHardMaxRows() <= 0 ? 1000 : properties.getHardMaxRows();
    }

    private static String joinHealth(EsInspectJson.IndexRow row) {
        StringBuilder text = new StringBuilder();
        if (row.health() != null) {
            text.append(row.health());
        }
        if (row.status() != null) {
            text.append(text.length() > 0 ? "/" : "").append(row.status());
        }
        return text.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}

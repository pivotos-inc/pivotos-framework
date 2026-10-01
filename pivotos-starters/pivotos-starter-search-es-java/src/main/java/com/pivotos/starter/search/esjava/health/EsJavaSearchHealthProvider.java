package com.pivotos.starter.search.esjava.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import com.pivotos.starter.search.api.health.SearchUnavailableReason;
import com.pivotos.starter.search.api.spi.SearchHealthProvider;
import com.pivotos.starter.search.esjava.support.EsRestSupport;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ES 健康采集实现（es-java，一套实现覆盖 ES 7.17 / 8.x / 9.x）。
 *
 * <h3>采集端点（只用各大版本都支持的口径）</h3>
 * <ul>
 *   <li>{@code GET /_cluster/health}——健康色、节点数、分片数；</li>
 *   <li>{@code GET /_cat/nodes?format=json}——节点明细（角色码 7.x 与 8.x/9.x 字面值不同，原样带出）；</li>
 *   <li>{@code GET /_cat/indices?format=json&bytes=b}——索引明细（文档数、存储字节数）；</li>
 *   <li>{@code GET /_nodes/stats/jvm}——JVM 堆。</li>
 * </ul>
 *
 * <h3>降级口径（硬性）</h3>
 * 启动期自检未通过 / 连接不可达 / 解析异常，<b>一律返回 {@code available=false} 快照 + 原因文案，
 * 绝不抛异常</b>——监控页是只读旁路，ES 挂了要能看出「ES 挂了」，而不是页面 500。
 * <p>启动期自检已失败时<b>不再发起网络请求</b>（探测结论是启动期缓存的，重复请求只会拖慢页面）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class EsJavaSearchHealthProvider implements SearchHealthProvider {

    private static final Logger log = LoggerFactory.getLogger(EsJavaSearchHealthProvider.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * {@code _cat/nodes} 只取需要的列。
     * <p>{@code load_1m} 在部分容器/平台可能为空，解析层已做空安全。
     */
    private static final String CAT_NODES = "/_cat/nodes?format=json"
            + "&h=name,ip,version,node.role,master,heap.percent,ram.percent,cpu,load_1m";

    /**
     * {@code _cat/indices} 用 {@code bytes=b} 让 store.size 直接给字节数（不接受 "3.2kb" 这类人类可读值）。
     */
    private static final String CAT_INDICES = "/_cat/indices?format=json&bytes=b"
            + "&h=index,health,status,docs.count,store.size,pri,rep";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ElasticsearchClient client;
    /** 启动期自检结论（false 时直接返回降级快照，不再打网络） */
    private final boolean available;
    /** 启动期探测到的服务端版本 */
    private final String serverVersion;

    public EsJavaSearchHealthProvider(ElasticsearchClient client, boolean available, String serverVersion) {
        this.client = client;
        this.available = available;
        this.serverVersion = serverVersion;
    }

    @Override
    public SearchProviderType type() {
        return SearchProviderType.ES_JAVA;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public SearchHealthSnapshot collect() {
        String esJava = SearchProviderType.ES_JAVA.getCode();
        if (!available) {
            // 启动期自检未通过：结论已缓存，重复探测没有意义，直接给出「已回落 simple」的降级快照
            return SearchHealthSnapshot.unavailable(SearchProviderType.SIMPLE.getCode(), esJava, true,
                    SearchUnavailableReason.FALLBACK, "启动期自检未通过（服务端 " + serverVersion + "）");
        }
        RestClient rest = EsRestSupport.lowLevelClient(client);
        if (rest == null) {
            return SearchHealthSnapshot.unavailable(esJava, esJava, false,
                    SearchUnavailableReason.COLLECT_FAILED, "无法获取低层 HTTP 客户端");
        }
        try {
            SearchHealthSnapshot snapshot = new SearchHealthSnapshot();
            snapshot.setAvailable(true);
            snapshot.setImplementation(esJava);
            snapshot.setConfiguredType(esJava);
            snapshot.setFallback(false);
            snapshot.setServerVersion(serverVersion);
            snapshot.setCollectedAt(LocalDateTime.now().format(FMT));

            // ① 集群健康（必须成功，失败即判不可用）
            EsHealthJsonParser.applyClusterHealth(snapshot, request(rest, "/_cluster/health"));

            // ② 节点明细（旁路：失败只丢这一个区块）
            List<SearchHealthSnapshot.NodeInfo> nodes = orEmpty(bestEffort(() ->
                    EsHealthJsonParser.parseNodes(request(rest, CAT_NODES)), "/_cat/nodes"));
            snapshot.setNodes(nodes);
            if (!nodes.isEmpty()) {
                snapshot.setNodeCount(nodes.size());
            }

            // ③ 索引明细 + 汇总（旁路）
            List<SearchHealthSnapshot.IndexInfo> indices = orEmpty(bestEffort(() ->
                    EsHealthJsonParser.parseIndices(request(rest, CAT_INDICES)), "/_cat/indices"));
            snapshot.setIndices(indices);
            EsHealthJsonParser.applyIndexTotals(snapshot, indices);

            // ④ JVM 堆（旁路）
            bestEffort(() -> {
                EsHealthJsonParser.applyJvmStats(snapshot, request(rest, "/_nodes/stats/jvm"));
                return true;
            }, "/_nodes/stats/jvm");

            return snapshot;
        } catch (Exception e) {
            log.warn("[PivotOS][search] ES 健康采集失败：{}", rootMessage(e));
            return SearchHealthSnapshot.unavailable(esJava, esJava, false,
                    SearchUnavailableReason.COLLECT_FAILED, rootMessage(e));
        }
    }

    // ==================== 内部 ====================

    /**
     * 旁路采集：单个端点失败只丢该区块，不让整页不可用（打 debug 即可，页面会显示该项为 0/空）。
     */
    private <T> T bestEffort(IoSupplier<T> supplier, String endpoint) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("[PivotOS][search] ES 健康采集：{} 不可用（该指标置空）：{}", endpoint, rootMessage(e));
            return null;
        }
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }

    /**
     * 原始 JSON GET。
     * <p>Accept / Content-Type 必须<b>成对</b>带上同一媒体类型（兼容模式下是
     * {@code compatible-with=7}），只带一个会被服务端判 {@code media_type_header_exception}（S127 实证）。
     */
    private JsonNode request(RestClient rest, String endpoint) throws IOException {
        Request request = new Request("GET", endpoint);
        String mediaType = EsRestSupport.mediaType(client);
        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.addHeader("Accept", mediaType);
        options.addHeader("Content-Type", mediaType);
        request.setOptions(options.build());

        Response response = rest.performRequest(request);
        int status = response.getStatusLine().getStatusCode();
        if (status >= 300) {
            throw new IOException("ES " + endpoint + " 返回 HTTP " + status);
        }
        try (InputStream in = response.getEntity().getContent()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return body.isBlank() ? null : MAPPER.readTree(body);
        }
    }

    /** 取根因消息，避免页面展示一长串嵌套异常前缀 */
    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}

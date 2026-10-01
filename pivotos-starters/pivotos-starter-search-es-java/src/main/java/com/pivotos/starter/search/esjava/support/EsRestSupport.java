package com.pivotos.starter.search.esjava.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.ElasticsearchTransportBase;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.RestClient;

import java.util.Map;

/**
 * 低层客户端与媒体类型工具（建索引原始 JSON 与 ES 健康采集共用）。
 * <p>两个用途都需要「绕开类型化 API 发原始 JSON」——原因不同但手段相同：
 * <ul>
 *   <li>建索引：8.x 客户端把 {@code match_mapping_type} 序列化成数组，ES 7.17 拒绝；</li>
 *   <li>健康采集：{@code _cat/*} 与 {@code _nodes/stats} 的响应结构在两个大版本上有字段差异，
 *       读原始 JSON 自己挑字段最稳，不必与客户端的类型定义赛跑。</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsRestSupport {

    private EsRestSupport() {
    }

    /**
     * 取底层 {@link RestClient}（拿不到时为 null，如测试替身）。
     * <p>{@code RestClientTransport#restClient()} 在 7.17 / 8.19 客户端上都是 public。
     */
    public static RestClient lowLevelClient(ElasticsearchClient client) {
        ElasticsearchTransport transport = client == null ? null : client._transport();
        return transport instanceof RestClientTransport rest ? rest.restClient() : null;
    }

    /**
     * 当前客户端实际使用的媒体类型（取传输层 Accept 头）。
     * <p><b>为什么取传输层而不是 RestClient 默认头</b>：传输层头会覆盖 RestClient 默认头，
     * 只配后者等于没配（S127 实证）。
     */
    public static String mediaType(ElasticsearchClient client) {
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
    public static boolean isCompatibilityMode(ElasticsearchClient client) {
        return mediaType(client).contains("compatible-with=7");
    }
}

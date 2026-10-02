package com.pivotos.starter.search.esjava.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.ElasticsearchTransportBase;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * elasticsearch-java 客户端工厂。
 * <p>自行构造客户端（不依赖任何 Spring Boot 对 ES 的自动装配）：Easy-ES / ES 官方 starter 的
 * 自动配置基线是 Spring Boot 2.7 / 3.x，与本项目的 Spring Boot 4.1 不匹配，故一律自建。
 *
 * <h3>多版本支持（7.17 / 8.x / 9.x）</h3>
 * 同一份客户端（8.19.18）按开关决定 wire 形态：
 * <ul>
 *   <li>{@code compatibility-mode=false}（默认）：发 {@code compatible-with=8}，面向 ES 8.x / 9.x
 *       ——9.5.3 的 {@code minimum_wire_compatibility_version=8.19.0}，实测直通；</li>
 *   <li>{@code compatibility-mode=true}：发 {@code compatible-with=7}（官方 compatibility header），
 *       让 8.x 客户端降到 7.x wire 形态，用于连 ES 7.17 服务端。</li>
 * </ul>
 * <b>该开关只影响请求头，不影响编译期依赖</b>——因此一套实现覆盖三个大版本，不需要 shade 双客户端。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsJavaClientFactory {

    /**
     * 兼容模式的媒体类型：Accept / Content-Type 两个头必须<b>同时</b>带同一 compatible-with 值，
     * 只带一个会被服务端判 {@code media_type_header_exception}（ES 9.5.3 实测）。
     */
    public static final String COMPATIBILITY_MEDIA_TYPE_7 =
            "application/vnd.elasticsearch+json; compatible-with=7";

    private EsJavaClientFactory() {
    }

    public static ElasticsearchClient create(SearchProperties.EsJava config) {
        RestClient restClient = createRestClient(config);
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(),
                buildTransportOptions(isCompatibilityMode(config)));
        return new ElasticsearchClient(transport);
    }

    /**
     * 低层 RestClient（建索引用它发原始 JSON——见 {@code EsJavaSearchProvider#createIndexIfAbsent}
     * 的版本差异说明）。与 {@link #create} 共用同一套地址/认证/超时配置。
     */
    public static RestClient createRestClient(SearchProperties.EsJava config) {
        if (config == null || config.getUris() == null || config.getUris().isEmpty()) {
            throw new SearchException(SearchErrorCode.PROVIDER_NOT_FOUND,
                    "pivotos.search.es-java.uris 未配置");
        }
        HttpHost[] hosts = config.getUris().stream()
                .map(EsJavaClientFactory::toHttpHost)
                .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(hosts)
                .setRequestConfigCallback(req -> req
                        .setConnectTimeout(config.getConnectTimeout())
                        .setSocketTimeout(config.getSocketTimeout()));

        List<Header> defaultHeaders = new ArrayList<>(buildAuthHeaders(config.getUsername(), config.getPassword()));
        if (isCompatibilityMode(config)) {
            defaultHeaders.add(new BasicHeader("Accept", COMPATIBILITY_MEDIA_TYPE_7));
            defaultHeaders.add(new BasicHeader("Content-Type", COMPATIBILITY_MEDIA_TYPE_7));
        }
        if (!defaultHeaders.isEmpty()) {
            builder.setDefaultHeaders(defaultHeaders.toArray(new Header[0]));
        }
        return builder.build();
    }

    /**
     * 传输层请求头。
     * <p>客户端默认发 {@code compatible-with=8}（取自 {@link ElasticsearchTransportBase#JSON_CONTENT_TYPE}）；
     * 这里显式复写是为了让兼容模式真正生效——传输层头会覆盖 RestClient 默认头，只设后者不够。
     */
    private static RestClientOptions buildTransportOptions(boolean compatibilityMode) {
        String mediaType = compatibilityMode
                ? COMPATIBILITY_MEDIA_TYPE_7
                : ElasticsearchTransportBase.JSON_CONTENT_TYPE;
        RestClientOptions.Builder options = new RestClientOptions.Builder(RequestOptions.DEFAULT.toBuilder());
        options.setHeader("Accept", mediaType);
        options.setHeader("Content-Type", mediaType);
        return options.build();
    }

    private static boolean isCompatibilityMode(SearchProperties.EsJava config) {
        return config != null && config.isCompatibilityMode();
    }

    private static HttpHost toHttpHost(String uri) {
        try {
            return HttpHost.create(uri);
        } catch (RuntimeException e) {
            throw new SearchException(SearchErrorCode.PROVIDER_NOT_FOUND, "ES 地址不合法：" + uri);
        }
    }

    private static List<Header> buildAuthHeaders(String username, String password) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        return List.of(new BasicHeader("Authorization", "Basic " + token));
    }
}

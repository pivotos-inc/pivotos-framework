package com.pivotos.starter.search.esjava.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * elasticsearch-java 客户端工厂。
 * <p>自行构造客户端（不依赖任何 Spring Boot 对 ES 的自动装配）：Easy-ES / ES 官方 starter 的
 * 自动配置基线是 Spring Boot 2.7 / 3.x，与本项目的 Spring Boot 4.1 不匹配，故一律自建。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsJavaClientFactory {

    private EsJavaClientFactory() {
    }

    public static ElasticsearchClient create(SearchProperties.EsJava config) {
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

        List<Header> defaultHeaders = buildAuthHeaders(config.getUsername(), config.getPassword());
        if (!defaultHeaders.isEmpty()) {
            builder.setDefaultHeaders(defaultHeaders.toArray(new Header[0]));
        }

        RestClient restClient = builder.build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
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

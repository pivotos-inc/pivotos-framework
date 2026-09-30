package com.pivotos.starter.search.easyes.client;

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
 * Easy-ES 客户端工厂。
 * <p>Easy-ES 3.0.2 的底层就是 elasticsearch-java（被其锁在 7.17.28），因此这里直接构造
 * 官方客户端喂给 {@code BaseEsMapperImpl}——绕开 easy-es-spring 的 Spring Boot 2.7 自动装配。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EasyEsClientFactory {

    private EasyEsClientFactory() {
    }

    public static ElasticsearchClient create(SearchProperties.EasyEs config) {
        if (config == null || config.getUris() == null || config.getUris().isEmpty()) {
            throw new SearchException(SearchErrorCode.PROVIDER_NOT_FOUND,
                    "pivotos.search.easy-es.uris 未配置");
        }
        HttpHost[] hosts = config.getUris().stream()
                .map(EasyEsClientFactory::toHttpHost)
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

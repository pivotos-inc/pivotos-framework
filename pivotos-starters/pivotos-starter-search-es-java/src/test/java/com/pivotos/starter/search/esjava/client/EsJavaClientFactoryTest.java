package com.pivotos.starter.search.esjava.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * es-java 客户端工厂：只验证「构造期」行为（RestClient 是惰性连接，不会在建客户端时握手），
 * 故无需 ES 服务端即可跑。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EsJavaClientFactoryTest {

    @Test
    void shouldCreateClientWithoutConnecting() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of("http://localhost:9200"));

        ElasticsearchClient client = EsJavaClientFactory.create(config);
        assertNotNull(client);
    }

    @Test
    void shouldRejectEmptyUris() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of());

        SearchException e = assertThrows(SearchException.class, () -> EsJavaClientFactory.create(config));
        assertEquals(SearchErrorCode.PROVIDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void shouldRejectNullConfig() {
        SearchException e = assertThrows(SearchException.class, () -> EsJavaClientFactory.create(null));
        assertEquals(SearchErrorCode.PROVIDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void shouldRejectIllegalUri() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of("not a uri"));

        SearchException e = assertThrows(SearchException.class, () -> EsJavaClientFactory.create(config));
        assertEquals(SearchErrorCode.PROVIDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void shouldSupportBasicAuth() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of("http://localhost:9200"));
        config.setUsername("elastic");
        config.setPassword("secret");

        assertNotNull(EsJavaClientFactory.create(config));
    }

    /**
     * 兼容模式接线：置 true 时客户端必须发 {@code compatible-with=7}。
     * <b>只看传输层头</b>——客户端默认发 compatible-with=8，若只改 RestClient 默认头会被覆盖
     * （ES 7.17 实测：只改默认头时建索引仍被拒）。
     */
    @Test
    void shouldSendCompatibilityHeaderWhenModeEnabled() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of("http://localhost:9200"));
        config.setCompatibilityMode(true);

        ElasticsearchClient client = EsJavaClientFactory.create(config);

        assertTrue(acceptHeader(client).contains("compatible-with=7"),
                "兼容模式必须把 Accept 降到 compatible-with=7，实际：" + acceptHeader(client));
        assertTrue(contentTypeHeader(client).contains("compatible-with=7"),
                "Accept 与 Content-Type 必须成对，实际：" + contentTypeHeader(client));
    }

    @Test
    void shouldKeepDefaultMediaTypeWhenModeDisabled() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of("http://localhost:9200"));

        ElasticsearchClient client = EsJavaClientFactory.create(config);

        assertTrue(acceptHeader(client).contains("compatible-with=8"),
                "默认应维持 compatible-with=8（连 8.x/9.x 服务端），实际：" + acceptHeader(client));
    }

    private static String acceptHeader(ElasticsearchClient client) {
        return header(client, "Accept");
    }

    private static String contentTypeHeader(ElasticsearchClient client) {
        return header(client, "Content-Type");
    }

    private static String header(ElasticsearchClient client, String name) {
        return client._transport().options().headers().stream()
                .filter(e -> name.equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }
}

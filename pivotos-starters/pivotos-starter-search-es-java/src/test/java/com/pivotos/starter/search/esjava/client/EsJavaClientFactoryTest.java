package com.pivotos.starter.search.esjava.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}

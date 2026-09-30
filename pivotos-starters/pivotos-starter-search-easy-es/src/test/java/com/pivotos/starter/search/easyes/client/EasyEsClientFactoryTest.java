package com.pivotos.starter.search.easyes.client;

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
 * Easy-ES 客户端工厂：只验证构造期行为（RestClient 惰性连接，无需 ES 服务端）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EasyEsClientFactoryTest {

    @Test
    void shouldCreateClientWithoutConnecting() {
        SearchProperties.EasyEs config = new SearchProperties.EasyEs();
        config.setUris(List.of("http://localhost:9200"));
        assertNotNull(EasyEsClientFactory.create(config));
    }

    @Test
    void shouldRejectEmptyUris() {
        SearchProperties.EasyEs config = new SearchProperties.EasyEs();
        config.setUris(List.of());
        SearchException e = assertThrows(SearchException.class, () -> EasyEsClientFactory.create(config));
        assertEquals(SearchErrorCode.PROVIDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void shouldRejectNullConfig() {
        SearchException e = assertThrows(SearchException.class, () -> EasyEsClientFactory.create(null));
        assertEquals(SearchErrorCode.PROVIDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void shouldSupportBasicAuth() {
        SearchProperties.EasyEs config = new SearchProperties.EasyEs();
        config.setUris(List.of("http://localhost:9200"));
        config.setUsername("elastic");
        config.setPassword("secret");
        assertNotNull(EasyEsClientFactory.create(config));
    }
}

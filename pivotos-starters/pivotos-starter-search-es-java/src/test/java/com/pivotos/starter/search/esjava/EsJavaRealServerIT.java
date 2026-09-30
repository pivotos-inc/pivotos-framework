package com.pivotos.starter.search.esjava;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import com.pivotos.starter.search.esjava.client.EsJavaClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * es-java 实现的「真实 ES 服务端」集成测试：清偿 S121 遗留①——此前只验证了「条件树 → 原生 DSL」的
 * 构建正确性，网络执行链路从未在真 ES 上跑过。
 * <p>默认<b>跳过</b>（无 ES 时不让全量 IT 变红）；需要真连时给环境变量：
 * <pre>
 * PIVOTOS_ES_URIS=http://host:9200 PIVOTOS_ES_USERNAME=elastic PIVOTOS_ES_PASSWORD=xxx \
 *   mvn -pl pivotos-starters/pivotos-starter-search-es-java test -Dtest=EsJavaRealServerIT
 * </pre>
 * 注意：ES 是近实时（默认 1s refresh），写入后需等待才能检到——本测试显式 sleep，生产链路同理
 * （写后立刻翻页可能看不到刚写的那条，属 ES 语义而非缺陷）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@EnabledIfEnvironmentVariable(named = "PIVOTOS_ES_URIS", matches = ".+")
class EsJavaRealServerIT {

    private static ElasticsearchClient client;
    private static EsJavaSearchProvider provider;
    private static String index;

    @BeforeAll
    static void setUp() {
        SearchProperties.EsJava config = new SearchProperties.EsJava();
        config.setUris(List.of(System.getenv("PIVOTOS_ES_URIS").split(",")));
        config.setUsername(System.getenv().getOrDefault("PIVOTOS_ES_USERNAME", ""));
        config.setPassword(System.getenv().getOrDefault("PIVOTOS_ES_PASSWORD", ""));
        client = EsJavaClientFactory.create(config);
        provider = new EsJavaSearchProvider(client);
        index = "pivot-it-" + UUID.randomUUID().toString().substring(0, 8);
        provider.createIndexIfAbsent(index);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }

    @Test
    void shouldRoundTripOnRealServer() throws Exception {
        // 1) 写入三条（时间用字符串形态——与「实体 → JSON → Map」后的真实形态一致）
        provider.index(doc("1", Map.of("module", "用户管理", "status", 0, "operTime", "2026-09-01 10:00:00")));
        provider.index(doc("2", Map.of("module", "用户管理", "status", 1, "operTime", "2026-09-20 10:00:00")));
        provider.index(doc("3", Map.of("module", "流程管理", "status", 0, "operTime", "2026-09-25 10:00:00")));
        Thread.sleep(1500); // ES 近实时：默认 1s refresh

        // 2) 全量 + count
        assertEquals(3L, provider.count(request(List.of(), 1, 10, null)));

        // 3) 模糊 + 精确 + 时间区间（与操作日志链路同形态）
        SearchResult filtered = provider.search(request(List.of(
                SearchCriteria.of("module", SearchOp.LIKE, SearchLogic.AND, "用户"),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 0),
                SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                        "2026-08-01 00:00:00", "2026-09-10 00:00:00")),
                1, 10, null));
        assertEquals(1L, filtered.getTotal(), "module 模糊 + status 精确 + 时间区间 应只命中 1 条");
        assertEquals("1", filtered.getHits().get(0).getId());

        // 3.5) 条件侧传「时间对象」（不是字符串）——业务里 Lambda 条件拿到的就是 LocalDateTime。
        // 这里锁死一个真机才暴露的缺陷：曾把时间转 epoch 毫秒与 keyword 字符串比大小，命中恒为 0。
        SearchResult byTemporal = provider.search(request(List.of(
                SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                        LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 10, 0, 0))),
                1, 10, null));
        assertEquals(1L, byTemporal.getTotal(), "时间对象条件必须落成索引里的时间字符串形态再比较");

        // 3.6) 中文精确匹配（text 分词字段上 term 会命中 0，keyword 才对）
        SearchResult byChineseEq = provider.search(request(List.of(
                SearchCriteria.of("module", SearchOp.EQ, SearchLogic.AND, "用户管理")), 1, 10, null));
        assertEquals(2L, byChineseEq.getTotal(), "中文精确 EQ 必须命中（索引里字符串一律 keyword）");

        // 4) 排序 + 分页
        SearchResult ordered = provider.search(request(List.of(), 1, 2, List.of(SearchOrder.desc("operTime"))));
        assertEquals(3L, ordered.getTotal());
        assertEquals(2, ordered.getHits().size());
        assertEquals("3", ordered.getHits().get(0).getId(), "按 operTime 倒序，最新在前");

        // 5) 删除后不可检
        provider.delete(index, "1");
        Thread.sleep(1500);
        assertEquals(2L, provider.count(request(List.of(), 1, 10, null)));
        assertTrue(provider.existsIndex(index));
        assertFalse(provider.search(request(List.of(), 1, 10, null))
                .getHits().stream().map(SearchHit::getId).toList().contains("1"));
    }

    private SearchDocument doc(String id, Map<String, Object> source) {
        return SearchDocument.of(index, id, source);
    }

    private SearchRequest request(List<SearchCriteria> criteria, int pageNum, int pageSize, List<SearchOrder> orders) {
        return SearchRequest.of(index, criteria, orders, pageNum, pageSize);
    }
}

package com.pivotos.starter.search.easyes;

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
import com.pivotos.starter.search.easyes.client.EasyEsClientFactory;
import com.pivotos.starter.search.easyes.client.EasyEsMapperFactory;
import org.dromara.easyes.core.kernel.BaseEsMapperImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Easy-ES 路线的「真实 ES 服务端」集成测试（<b>面向已在用 Easy-ES 的项目</b>）。
 * <p>easy-es-core 3.0.2 内嵌的 es-java 被锁在 <b>7.17.28</b>，因此本实现只面向 ES 7.17 服务端，
 * 用 dev B（175.24.176.176:9201，ES 7.17.28）验证；<b>不需要也不能开兼容头</b>
 * ——这是它与 es-java 路线的关键差异（es-java 用 8.19 客户端 + compatible-with=7 连 7.17）。
 *
 * <p>默认<b>跳过</b>；真连时：
 * <pre>
 * PIVOTOS_ES_URIS=http://175.24.176.176:9201 PIVOTOS_ES_USERNAME=elastic PIVOTOS_ES_PASSWORD=xxx \
 *   mvn -pl pivotos-starters/pivotos-starter-search-easy-es test -Dtest=EasyEsRealServerIT
 * </pre>
 *
 * <p>断言项与 es-java 路线逐条对齐，便于对照两条路线的行为差异：
 * 索引自动创建 / 中文精确 EQ / 时间对象 BETWEEN / 端到端写入-检索-删除。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@EnabledIfEnvironmentVariable(named = "PIVOTOS_ES_URIS", matches = ".+")
class EasyEsRealServerIT {

    private static ElasticsearchClient client;
    private static EasyEsSearchProvider provider;

    @BeforeAll
    static void setUp() {
        SearchProperties.EasyEs config = new SearchProperties.EasyEs();
        config.setUris(List.of(System.getenv("PIVOTOS_ES_URIS").split(",")));
        config.setUsername(System.getenv().getOrDefault("PIVOTOS_ES_USERNAME", ""));
        config.setPassword(System.getenv().getOrDefault("PIVOTOS_ES_PASSWORD", ""));
        client = EasyEsClientFactory.create(config);
        BaseEsMapperImpl<HashMap> mapper = EasyEsMapperFactory.createMapper(client);
        provider = new EasyEsSearchProvider(mapper, client);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client._transport().close();
        }
    }

    /** 索引自动创建 + dynamic_templates 生效（无 mapping 时字符串会被动态映射成 text，中文 EQ 恒 0） */
    @Test
    void shouldCreateIndexIfAbsent() {
        String index = tempIndex("create");
        try {
            assertFalse(provider.existsIndex(index));
            provider.createIndexIfAbsent(index);
            assertTrue(provider.existsIndex(index));
        } finally {
            deleteIndex(index);
        }
    }

    /** 中文精确 EQ / 模糊 LIKE */
    @Test
    void shouldMatchChinese() {
        String index = tempIndex("cn");
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "status", 0)));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "status", 1)));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "status", 0)));
            refresh(index);

            assertEquals(2L, provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.EQ, SearchLogic.AND, "用户管理")), 1, 10, null)).getTotal(),
                    "中文精确 EQ 必须命中（字符串须落成 keyword）");
            assertEquals(2L, provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.LIKE, SearchLogic.AND, "用户")), 1, 10, null)).getTotal(),
                    "中文模糊 LIKE 必须命中");
        } finally {
            deleteIndex(index);
        }
    }

    /**
     * 时间条件（字符串 + LocalDateTime 两种入参）。
     * <p>与 es-java 路线的差异点在此：es-java 侧由 {@code EsJavaQueryBuilder#normalize} 统一归一成
     * {@code yyyyy-MM-dd HH:mm:ss} 定长字符串；easy-es 侧条件交给 {@code LambdaEsQueryWrapper} 编译，
     * 时间对象能否落成同一形态需实测——本用例即为该差异的取证点。
     */
    @Test
    void shouldRangeOnTime() {
        String index = tempIndex("time");
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "operTime", "2026-09-01 10:00:00")));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "operTime", "2026-09-20 10:00:00")));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "operTime", "2026-09-25 10:00:00")));
            refresh(index);

            assertEquals(1L, provider.search(request(index, List.of(
                    SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                            "2026-08-01 00:00:00", "2026-09-10 00:00:00")), 1, 10, null)).getTotal(),
                    "时间字符串区间应只命中 1 条");
            assertEquals(1L, provider.search(request(index, List.of(
                    SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                            LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 10, 0, 0))),
                    1, 10, null)).getTotal(),
                    "时间对象条件必须落成索引里的时间字符串形态再比较");
        } finally {
            deleteIndex(index);
        }
    }

    /** 端到端：写入 → 计数 → 组合条件 → 排序 → 删除后不可检 */
    @Test
    void shouldRoundTripOnRealServer() {
        String index = tempIndex("rt");
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "status", 0, "operTime", "2026-09-01 10:00:00")));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "status", 1, "operTime", "2026-09-20 10:00:00")));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "status", 0, "operTime", "2026-09-25 10:00:00")));
            refresh(index);

            assertEquals(3L, provider.count(request(index, List.of(), 1, 10, null)));

            SearchResult filtered = provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.LIKE, SearchLogic.AND, "用户"),
                    SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 0)), 1, 10, null));
            assertEquals(1L, filtered.getTotal());
            assertEquals("1", filtered.getHits().get(0).getId());

            SearchResult ordered = provider.search(request(index, List.of(), 1, 2,
                    List.of(SearchOrder.desc("operTime"))));
            assertEquals(3L, ordered.getTotal());
            assertEquals(2, ordered.getHits().size());
            assertEquals("3", ordered.getHits().get(0).getId(), "按 operTime 倒序，最新在前");

            provider.delete(index, "1");
            refresh(index);
            assertFalse(provider.search(request(index, List.of(), 1, 10, null))
                    .getHits().stream().map(SearchHit::getId).toList().contains("1"));
        } finally {
            deleteIndex(index);
        }
    }

    // ==================== 内部 ====================

    /**
     * easy-es 路线未接 refresh-on-write 开关（与 es-java 的行为差异，记录在案），
     * 测试里显式刷一次保证可读——ES 默认近实时（1s），生产链路同样受此约束。
     */
    private static void refresh(String index) {
        try {
            client.indices().refresh(co.elastic.clients.elasticsearch.indices.RefreshRequest.of(r -> r.index(index)));
        } catch (Exception e) {
            // 刷新失败不影响后续断言的真实性（最坏是命中数少，不会假绿）
        }
    }

    private static String tempIndex(String tag) {
        return ("pivot-easy-it-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase(Locale.ROOT);
    }

    private static void deleteIndex(String index) {
        try {
            client.indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        } catch (Exception e) {
            // 清理失败不影响断言结论
        }
    }

    private static SearchDocument doc(String index, String id, Map<String, Object> source) {
        return SearchDocument.of(index, id, source);
    }

    private static SearchRequest request(String index, List<SearchCriteria> criteria, int pageNum, int pageSize,
                                         List<SearchOrder> orders) {
        return SearchRequest.of(index, criteria, orders, pageNum, pageSize);
    }
}

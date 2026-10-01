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
import com.pivotos.starter.search.esjava.support.EsServerVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * es-java 实现的「真实 ES 服务端」集成测试（<b>可参数化跑多个服务端</b>）。
 * <p>清偿 S121 遗留①与 S122 遗留①：此前只验证了「条件树 → 原生 DSL」的构建正确性，
 * 且只在 ES 9.5.3 上跑过一次；本轮要求 <b>7.17 与 9.x 两个服务端都必须绿</b>——
 * 版本差异（dynamic_templates 的 match_mapping_type 形态、兼容头是否被接受）只有双目标才能钉住。
 *
 * <p>默认<b>跳过</b>（无 ES 时不让全量 IT 变红）。需要真连时给环境变量：
 * <pre>
 * # 推荐：一次跑多个目标（name|uri|user|password|compatibilityMode，分号分隔）
 * PIVOTOS_ES_TARGETS='es9|http://175.24.176.176:9200|elastic|pwd|false;es717|http://175.24.176.176:9201|elastic|pwd|true' \
 *   mvn -pl pivotos-starters/pivotos-starter-search-es-java test -Dtest=EsJavaRealServerIT
 *
 * # 兼容旧口径：单目标（PIVOTOS_ES_URIS + 可选 PIVOTOS_ES_COMPATIBILITY_MODE）
 * PIVOTOS_ES_URIS=http://host:9200 PIVOTOS_ES_USERNAME=elastic PIVOTOS_ES_PASSWORD=xxx \
 *   mvn -pl pivotos-starters/pivotos-starter-search-es-java test -Dtest=EsJavaRealServerIT
 * </pre>
 *
 * <p>断言项（三条对应 S122 真机暴露的三个病灶）：
 * <ol>
 *   <li><b>索引自动创建成功</b>——且 dynamic_templates 生效（字符串落成 keyword）；</li>
 *   <li><b>中文 keyword 精确 EQ</b>——打 text 分词字段会命中 0（S122 缺陷①）；</li>
 *   <li><b>时间对象 BETWEEN</b>——条件侧传 LocalDateTime 须落成索引内的定长字符串再比较（S122 缺陷②）。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@EnabledIf("targetsConfigured")
class EsJavaRealServerIT {

    /** 一个待测服务端 */
    record EsTarget(String name, List<String> uris, String username, String password, boolean compatibilityMode) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Map<String, ElasticsearchClient> CLIENTS = new LinkedHashMap<>();

    static boolean targetsConfigured() {
        return !targets().isEmpty();
    }

    static List<EsTarget> targets() {
        String multi = System.getenv("PIVOTOS_ES_TARGETS");
        if (multi != null && !multi.isBlank()) {
            List<EsTarget> parsed = new ArrayList<>();
            for (String raw : multi.split(";")) {
                if (raw.isBlank()) {
                    continue;
                }
                String[] f = raw.trim().split("\\|");
                if (f.length < 2) {
                    throw new IllegalStateException(
                            "PIVOTOS_ES_TARGETS 片段格式应为 name|uri|username|password|compatibilityMode，实际：" + raw);
                }
                parsed.add(new EsTarget(
                        f[0].trim(),
                        Arrays.stream(f[1].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                        f.length > 2 ? f[2].trim() : "",
                        f.length > 3 ? f[3].trim() : "",
                        f.length > 4 && Boolean.parseBoolean(f[4].trim())));
            }
            return parsed;
        }
        String uris = System.getenv("PIVOTOS_ES_URIS");
        if (uris != null && !uris.isBlank()) {
            return List.of(new EsTarget("default",
                    Arrays.stream(uris.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                    System.getenv().getOrDefault("PIVOTOS_ES_USERNAME", ""),
                    System.getenv().getOrDefault("PIVOTOS_ES_PASSWORD", ""),
                    Boolean.parseBoolean(System.getenv().getOrDefault("PIVOTOS_ES_COMPATIBILITY_MODE", "false"))));
        }
        return List.of();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        for (ElasticsearchClient c : CLIENTS.values()) {
            c._transport().close();
        }
        CLIENTS.clear();
    }

    // ==================== 用例 ====================

    /** 病灶①：索引自动创建 + dynamic_templates 生效（建成后字符串字段必须是 keyword，否则后面两条都不会过） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldCreateIndexIfAbsent(EsTarget target) {
        String index = tempIndex("create");
        EsJavaSearchProvider provider = provider(target);
        try {
            assertFalse(provider.existsIndex(index), "测试索引不应预先存在");
            provider.createIndexIfAbsent(index);
            assertTrue(provider.existsIndex(index), "createIndexIfAbsent 必须在两个版本上都能真正建出索引");
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 病灶②：中文精确 EQ（索引里字符串是 keyword 才命中；落成 text 被 standard 分词后恒为 0） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldMatchChineseKeywordExactly(EsTarget target) {
        String index = tempIndex("cn");
        EsJavaSearchProvider provider = provider(target);
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "status", 0)));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "status", 1)));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "status", 0)));

            SearchResult eq = provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.EQ, SearchLogic.AND, "用户管理")), 1, 10, null));
            assertEquals(2L, eq.getTotal(), "中文精确 EQ 必须命中（索引里字符串一律 keyword，不分詞）");

            SearchResult like = provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.LIKE, SearchLogic.AND, "用户")), 1, 10, null));
            assertEquals(2L, like.getTotal(), "中文模糊 LIKE 必须命中");
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 病灶③：时间对象 BETWEEN（条件侧是 LocalDateTime，索引侧是定长字符串，两侧形态必须归一） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldRangeOnTemporalObject(EsTarget target) {
        String index = tempIndex("time");
        EsJavaSearchProvider provider = provider(target);
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "operTime", "2026-09-01 10:00:00")));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "operTime", "2026-09-20 10:00:00")));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "operTime", "2026-09-25 10:00:00")));

            SearchResult byString = provider.search(request(index, List.of(
                    SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                            "2026-08-01 00:00:00", "2026-09-10 00:00:00")), 1, 10, null));
            assertEquals(1L, byString.getTotal(), "时间字符串区间应只命中 1 条");

            SearchResult byTemporal = provider.search(request(index, List.of(
                    SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                            LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 10, 0, 0))), 1, 10, null));
            assertEquals(1L, byTemporal.getTotal(), "时间对象条件必须落成索引里的时间字符串形态再比较");

            SearchResult ordered = provider.search(request(index, List.of(), 1, 2,
                    List.of(SearchOrder.desc("operTime"))));
            assertEquals(3L, ordered.getTotal());
            assertEquals(2, ordered.getHits().size());
            assertEquals("3", ordered.getHits().get(0).getId(), "按 operTime 倒序，最新在前");
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 端到端：写入 → 计数 → 组合条件 → 删除后不可检 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldRoundTripOnRealServer(EsTarget target) {
        String index = tempIndex("rt");
        EsJavaSearchProvider provider = provider(target);
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", Map.of("module", "用户管理", "status", 0, "operTime", "2026-09-01 10:00:00")));
            provider.index(doc(index, "2", Map.of("module", "用户管理", "status", 1, "operTime", "2026-09-20 10:00:00")));
            provider.index(doc(index, "3", Map.of("module", "流程管理", "status", 0, "operTime", "2026-09-25 10:00:00")));
            assertEquals(3L, provider.count(request(index, List.of(), 1, 10, null)));

            SearchResult filtered = provider.search(request(index, List.of(
                    SearchCriteria.of("module", SearchOp.LIKE, SearchLogic.AND, "用户"),
                    SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 0),
                    SearchCriteria.of("operTime", SearchOp.BETWEEN, SearchLogic.AND,
                            "2026-08-01 00:00:00", "2026-09-10 00:00:00")), 1, 10, null));
            assertEquals(1L, filtered.getTotal());
            assertEquals("1", filtered.getHits().get(0).getId());

            provider.delete(index, "1");
            assertEquals(2L, provider.count(request(index, List.of(), 1, 10, null)),
                    "ES 近实时：refreshOnWrite=true 时删除后立即可见");
            assertFalse(provider.search(request(index, List.of(), 1, 10, null))
                    .getHits().stream().map(SearchHit::getId).toList().contains("1"));
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 启动期版本探测：必须拿到真实版本且落在支持区间（否则 Provider 会判不可用并回落 simple） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldDetectSupportedServerVersion(EsTarget target) {
        EsJavaSearchProvider provider = provider(target);
        EsServerVersion version = provider.serverVersion();
        assertNotNull(version);
        assertFalse(version.isUnknown(), "应探测到真实的服务端版本号（探测失败说明 ES 不可达或兼容头被拒）");
        assertTrue(version.isSupported(),
                "服务端版本 " + version.raw() + " 不在支持区间 " + EsServerVersion.supportRangeText());
        assertTrue(provider.isAvailable(), "版本可用时 Provider 必须标记 available=true");
        assertEquals(target.compatibilityMode(), version.needsCompatibilityHeader(),
                "compatibility-mode 必须与服务端版本匹配（仅 7.x 需要兼容头）");
    }

    // ==================== 内部 ====================

    private static ElasticsearchClient client(EsTarget target) {
        return CLIENTS.computeIfAbsent(target.name(), k -> {
            SearchProperties.EsJava config = new SearchProperties.EsJava();
            config.setUris(target.uris());
            config.setUsername(target.username());
            config.setPassword(target.password());
            config.setCompatibilityMode(target.compatibilityMode());
            return EsJavaClientFactory.create(config);
        });
    }

    private static EsJavaSearchProvider provider(EsTarget target) {
        // refreshOnWrite=true：测试不做 sleep，写后立即可检（ES 默认 1s refresh 属近实时语义）
        return new EsJavaSearchProvider(client(target), true);
    }

    private static String tempIndex(String tag) {
        return ("pivot-it-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase(Locale.ROOT);
    }

    private static void deleteIndex(EsTarget target, String index) {
        try {
            client(target).indices().delete(new DeleteIndexRequest.Builder().index(index).build());
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

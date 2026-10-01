package com.pivotos.starter.search.esjava;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.score.ScoredSearchRequest;
import com.pivotos.starter.search.esjava.client.EsJavaClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * es-java <b>打分召回通道</b>的真实服务端集成测试（S128）。
 * <p>必须双目标：ES 9.5.3（9200，不可带兼容头）与 ES 7.17.28（9201，<b>必须 compatibility-mode=true</b>）。
 * 只跑一个版本等于没跑——S127 的真机经验是「类型化 API 的序列化形态可能与 7.17 不兼容」，
 * 本轮新增了 multi_match 查询与「已存在索引上追加 text 映射」两条链路，同样只有 7.17 能钉住。
 *
 * <pre>
 * PIVOTOS_ES_TARGETS='es9|http://175.24.176.176:9200|elastic|pwd|false;es717|http://175.24.176.176:9201|elastic|pwd|true' \
 *   mvn -pl pivotos-starters/pivotos-starter-search-es-java test -Dtest=EsJavaScoredRealServerIT
 * </pre>
 *
 * <p>默认 Skipped（无 PIVOTOS_ES_TARGETS/PIVOTOS_ES_URIS 时），不会让全量 IT 变红。
 *
 * <p>断言项对应 S128 的三条硬要求：
 * <ol>
 *   <li><b>分数非 0 且能区分相关度</b>（score 恒 0 = 通道没接上，等同 S122 遗留②没清偿）；</li>
 *   <li><b>全文映射真的建成了 text</b>（建不出来就只剩整值命中，中文关键词召回恒空）；</li>
 *   <li><b>确定性条件 + 打分可以叠加</b>（kbId 过滤与关键词必须同时生效）。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@EnabledIf("targetsConfigured")
class EsJavaScoredRealServerIT {

    private static final Map<String, ElasticsearchClient> CLIENTS = new LinkedHashMap<>();

    static boolean targetsConfigured() {
        return EsItTargets.configured();
    }

    static List<EsItTargets.EsTarget> targets() {
        return EsItTargets.targets();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        for (ElasticsearchClient c : CLIENTS.values()) {
            c._transport().close();
        }
        CLIENTS.clear();
    }

    // ==================== 用例 ====================

    /** 核心：全文字段建成 text，multi_match 给出非 0 的 BM25 分数且能区分相关度 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldScoreByBm25OnFullTextField(EsItTargets.EsTarget target) {
        String index = tempIndex("bm25");
        EsJavaSearchProvider provider = provider(target);
        provider.createFullTextIndexIfAbsent(index, List.of("content"));
        try {
            provider.index(doc(index, "1", "知识库检索的完整使用说明文档", 7L));
            provider.index(doc(index, "2", "知识库检索", 7L));
            provider.index(doc(index, "3", "知识库", 7L));
            provider.index(doc(index, "4", "流程管理与审批配置", 7L));

            SearchResult result = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"), List.of(), 10, 0D, 100));

            assertEquals(3, result.getTotal(), "未命中查询词的文档不进结果（ES 侧 match 未命中即不计分）");
            List<String> ids = result.getHits().stream().map(SearchHit::getId).toList();
            assertEquals(List.of("3", "2", "1"), ids, "短文本的 BM25 分数更高（长度归一生效）");
            assertTrue(result.getHits().get(0).getScore() > 0D, "分数不得恒 0 —— 那是 S122 遗留②本身");
            assertTrue(result.getHits().get(0).getScore() > result.getHits().get(1).getScore());
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 确定性过滤与打分必须叠加：只带关键词不带条件 = 跨知识库泄漏 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldCombineCriteriaWithScoring(EsItTargets.EsTarget target) {
        String index = tempIndex("crit");
        EsJavaSearchProvider provider = provider(target);
        provider.createFullTextIndexIfAbsent(index, List.of("content"));
        try {
            provider.index(doc(index, "1", "知识库检索说明", 7L));
            provider.index(doc(index, "2", "知识库检索说明", 8L));

            SearchResult result = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"),
                    List.of(SearchCriteria.of("kbId", SearchOp.EQ, SearchLogic.AND, 8L)),
                    10, 0D, 100));

            assertEquals(1, result.getTotal(), "硬性条件必须与打分同时生效（漏掉就是跨知识库召回）");
            assertEquals("2", result.getHits().get(0).getId());
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 阈值与 topK 必须真实生效（total 收敛到过阈值的条数） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldHonorMinScoreAndTopK(EsItTargets.EsTarget target) {
        String index = tempIndex("thr");
        EsJavaSearchProvider provider = provider(target);
        provider.createFullTextIndexIfAbsent(index, List.of("content"));
        try {
            provider.index(doc(index, "1", "知识库检索的完整使用说明文档", 7L));
            provider.index(doc(index, "2", "知识库检索", 7L));
            provider.index(doc(index, "3", "知识库", 7L));

            SearchResult all = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"), List.of(), 10, 0D, 100));
            double top = all.getHits().get(0).getScore();

            assertEquals(2, provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"), List.of(), 2, 0D, 100)).getHits().size(), "topK 截断");
            SearchResult filtered = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"), List.of(), 10, top, 100));
            assertEquals(1, filtered.getTotal(), "低于阈值的命中必须丢弃，total 同步收敛");
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 索引已存在（keyword 动态模板已建）时，追加 text 映射仍要能让全文通道打分 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldAppendTextMappingOnExistingIndex(EsItTargets.EsTarget target) {
        String index = tempIndex("append");
        EsJavaSearchProvider provider = provider(target);
        provider.createIndexIfAbsent(index);
        try {
            // 先按确定性索引建（字符串一律 keyword），再补全文映射（content → text）
            provider.createFullTextIndexIfAbsent(index, List.of("content"));
            provider.index(doc(index, "1", "知识库检索的完整使用说明文档", 7L));
            provider.index(doc(index, "2", "知识库", 7L));

            SearchResult result = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of("content"), List.of(), 10, 0D, 100));

            assertEquals(2, result.getTotal(), "补建的 text 映射必须生效，否则中文关键词只能整值命中（召回恒空）");
            assertEquals("2", result.getHits().get(0).getId());
        } finally {
            deleteIndex(target, index);
        }
    }

    /** 未指定打分字段时退化到本地重打分：仍必须给出非 0 分数，不能退化成 score 恒 0 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void shouldDegradeToLocalRescoringWhenFieldsAbsent(EsItTargets.EsTarget target) {
        String index = tempIndex("nofield");
        EsJavaSearchProvider provider = provider(target);
        provider.createIndexIfAbsent(index);
        try {
            provider.index(doc(index, "1", "知识库检索的完整使用说明文档", 7L));
            provider.index(doc(index, "2", "知识库", 7L));
            provider.index(doc(index, "3", "流程管理与审批配置", 7L));

            SearchResult result = provider.searchScored(ScoredSearchRequest.of(index, "知识库",
                    List.of(), List.of(), 10, 0D, 100));

            assertEquals(2, result.getTotal());
            assertTrue(result.getHits().get(0).getScore() > 0D,
                    "退化路径也必须给出真实分数（S128 的核心就是不许再出现 score 恒 0）");
        } finally {
            deleteIndex(target, index);
        }
    }

    // ==================== 内部 ====================

    private static ElasticsearchClient client(EsItTargets.EsTarget target) {
        return CLIENTS.computeIfAbsent(target.name(), k -> {
            com.pivotos.starter.search.api.config.SearchProperties.EsJava config =
                    new com.pivotos.starter.search.api.config.SearchProperties.EsJava();
            config.setUris(target.uris());
            config.setUsername(target.username());
            config.setPassword(target.password());
            config.setCompatibilityMode(target.compatibilityMode());
            return EsJavaClientFactory.create(config);
        });
    }

    private static EsJavaSearchProvider provider(EsItTargets.EsTarget target) {
        return new EsJavaSearchProvider(client(target), true);
    }

    private static com.pivotos.starter.search.api.document.SearchDocument doc(String index, String id,
                                                                             String content, Long kbId) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kbId", kbId);
        source.put("content", content);
        return com.pivotos.starter.search.api.document.SearchDocument.of(index, id, source);
    }

    private static String tempIndex(String tag) {
        return ("pivot-it-ft-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase(Locale.ROOT);
    }

    private static void deleteIndex(EsItTargets.EsTarget target, String index) {
        try {
            client(target).indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        } catch (Exception e) {
            // 清理失败不影响断言结论
        }
    }
}

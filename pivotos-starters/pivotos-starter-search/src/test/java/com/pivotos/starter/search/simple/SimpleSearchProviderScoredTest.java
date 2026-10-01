package com.pivotos.starter.search.simple;

import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.score.ScoredSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * simple 侧的<b>确定性打分召回</b>（S128）。
 * <p>这两条是本 Sprint 的硬要求，缺一即视为没做：
 * <ol>
 *   <li><b>分数必须非 0 且能区分相关度</b>——S122 遗留②的原话就是「simple 侧 score 恒 0」，
 *       那样全文通道等于没接，RRF 融合也只是把两路顺序硬拼；</li>
 *   <li><b>必须可复现</b>——同一份数据重复召回，顺序与分数逐位相同。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SimpleSearchProviderScoredTest {

    private static final String INDEX = "kb-chunk-doc";

    private SimpleSearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SimpleSearchProvider();
        provider.createIndexIfAbsent(INDEX);
        // 同一主题、不同长度：BM25 的长度归一会让「短而准」的排前面
        provider.index(doc("1", "知识库检索的完整使用说明文档"));
        provider.index(doc("2", "知识库检索"));
        provider.index(doc("3", "知识库"));
        provider.index(doc("4", "流程管理与审批配置"));
    }

    @Test
    void shouldScoreByBm25Descending() {
        SearchResult result = provider.searchScored(request("知识库", List.of("content"), 10, 0D, 100));

        assertEquals(3, result.getTotal(), "未命中任何查询词的文档不进结果");
        List<String> ids = result.getHits().stream().map(SearchHit::getId).toList();
        assertEquals(List.of("3", "2", "1"), ids, "短文档的 BM25 分数更高（长度归一生效）");
        assertTrue(result.getHits().get(0).getScore() > 0D, "分数不能恒 0");
        assertTrue(result.getHits().get(0).getScore() > result.getHits().get(1).getScore());
    }

    @Test
    void shouldBeDeterministicAcrossRepeatedCalls() {
        SearchResult first = provider.searchScored(request("知识库检索", List.of("content"), 10, 0D, 100));
        SearchResult second = provider.searchScored(request("知识库检索", List.of("content"), 10, 0D, 100));

        assertEquals(first.getHits().stream().map(SearchHit::getId).toList(),
                second.getHits().stream().map(SearchHit::getId).toList());
        for (int i = 0; i < first.getHits().size(); i++) {
            assertEquals(first.getHits().get(i).getScore(), second.getHits().get(i).getScore(), 0D,
                    "重复召回必须逐位一致（禁止随机/哈希序依赖）");
        }
    }

    @Test
    void shouldDegradeToIdOrderWithoutKeyword() {
        SearchResult result = provider.searchScored(request("", List.of("content"), 3, 0D, 100));

        assertEquals(List.of("1", "2", "3"), result.getHits().stream().map(SearchHit::getId).toList(),
                "无关键词 = 无相关性概念，退化为按条件取前 topK（id 升序保证可复现）");
        assertEquals(0D, result.getHits().get(0).getScore(), 0D);
        assertEquals(4, result.getTotal(), "total 是过阈值的总数，不受 topK 影响");
    }

    @Test
    void shouldTruncateToTopK() {
        SearchResult result = provider.searchScored(request("知识库", List.of("content"), 2, 0D, 100));
        assertEquals(2, result.getHits().size());
        assertEquals(3, result.getTotal(), "total 仍是过阈值的总数");
    }

    @Test
    void shouldDropHitsBelowMinScore() {
        double top = provider.searchScored(request("知识库", List.of("content"), 10, 0D, 100))
                .getHits().get(0).getScore();

        SearchResult filtered = provider.searchScored(request("知识库", List.of("content"), 10, top, 100));

        assertEquals(1, filtered.getTotal(), "低于阈值的命中必须丢弃，且 total 同步收敛");
        assertEquals("3", filtered.getHits().get(0).getId());
    }

    @Test
    void shouldCombineCriteriaAndKeyword() {
        List<SearchCriteria> criteria = List.of(
                SearchCriteria.of("kbId", SearchOp.EQ, SearchLogic.AND, 7L));
        provider.index(SearchDocument.of(INDEX, "5", source("知识库", 7L)));

        SearchResult result = provider.searchScored(ScoredSearchRequest.of(INDEX, "知识库",
                List.of("content"), criteria, 10, 0D, 100));

        assertEquals(1, result.getTotal(), "硬性条件与关键词必须同时生效（条件不过滤等于跨库泄漏）");
        assertEquals("5", result.getHits().get(0).getId());
    }

    @Test
    void shouldLimitScoringByCandidateWindow() {
        // 窗口 = 2：只有 id 最小的两条进打分（id=3 的那条即便相关度最高也看不到）
        // 注意窗口被 ScoredSearchRequest 归一化成 >= topK，故这里 topK 一并取 2
        SearchResult limited = provider.searchScored(request("知识库", List.of("content"), 2, 0D, 2));
        assertEquals(List.of("2", "1"), limited.getHits().stream().map(SearchHit::getId).toList());

        SearchResult full = provider.searchScored(request("知识库", List.of("content"), 2, 0D, 100));
        assertEquals(List.of("3", "2"), full.getHits().stream().map(SearchHit::getId).toList(),
                "窗口放开后 id=3（最短、BM25 最高）才进得来");
    }

    @Test
    void shouldReturnEmptyWhenIndexAbsent() {
        SearchResult result = provider.searchScored(request("知识库", List.of("content"), 10, 0D, 100));
        provider.clear();
        assertTrue(provider.searchScored(request("知识库", List.of("content"), 10, 0D, 100)).getHits().isEmpty(),
                "索引不存在时返回空结果，不得抛异常（检索是旁路，不能把业务拖垮）");
        assertEquals(3, result.getTotal());
    }

    @Test
    void shouldFallBackToAllStringFieldsWhenFieldsAbsent() {
        SearchResult result = provider.searchScored(request("流程管理", List.of(), 10, 0D, 100));
        assertEquals(1, result.getTotal(), "未指定字段时取全部字符串值参与打分");
        assertEquals("4", result.getHits().get(0).getId());
    }

    private static SearchDocument doc(String id, String content) {
        return SearchDocument.of(INDEX, id, source(content, 1L));
    }

    private static Map<String, Object> source(String content, Long kbId) {
        Map<String, Object> source = new HashMap<>();
        source.put("kbId", kbId);
        source.put("content", content);
        return source;
    }

    private static ScoredSearchRequest request(String keyword, List<String> fields, int topK,
                                               double minScore, int window) {
        return ScoredSearchRequest.of(INDEX, keyword, fields, List.of(), topK, minScore, window);
    }
}

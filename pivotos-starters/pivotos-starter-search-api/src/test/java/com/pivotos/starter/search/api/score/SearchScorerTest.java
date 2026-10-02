package com.pivotos.starter.search.api.score;

import com.pivotos.starter.search.api.document.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性 BM25 打分器（S128）。
 * <p>本测试存在的唯一理由：召回分数<b>必须可复现</b>。RAG 评测要对账、回归要能复现，
 * 一旦打分依赖哈希迭代顺序或浮点累加顺序，「同一条 query 两次召回顺序不同」就是必然，
 * 且这类 bug 在本地永远复现不了（只在并发/特定 JVM 下偶发）。故下面每条确定性断言都是硬门禁。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SearchScorerTest {

    // ==================== 分词 ====================

    @Test
    void shouldTokenizeWithUnigramAndBigram() {
        // 单字 + 双字滑窗，保持插入顺序去重（与 ai-kb 侧 Bm25Retriever 同口径）
        assertEquals(List.of("知", "知识", "识", "识库", "库", "库检", "检", "检索", "索"),
                SearchScorer.tokenize("知识库检索"));
    }

    @Test
    void shouldStripPunctuationBeforeTokenize() {
        assertEquals(List.of("a", "ab", "b"), SearchScorer.tokenize("a，b！"));
        assertEquals(List.of(), SearchScorer.tokenize("  "));
        assertEquals(List.of(), SearchScorer.tokenize(null));
    }

    // ==================== 打分语义 ====================

    @Test
    void shouldRankByBm25AndDropZeroScoreHits() {
        List<SearchHit> hits = List.of(
                SearchHit.of("1", 0D, Map.of("content", "知识库检索")),
                SearchHit.of("2", 0D, Map.of("content", "知识库")),
                SearchHit.of("3", 0D, Map.of("content", "流程管理")));

        List<SearchHit> ranked = SearchScorer.rank(hits, "知识库", List.of("content"));

        assertEquals(2, ranked.size(), "一个查询词都没命中的文档必须丢弃（与 ES 侧 match 未命中不进 _score 一致）");
        assertEquals("2", ranked.get(0).getId(), "同等内容下短文档 BM25 分数更高（长度归一）");
        assertEquals("1", ranked.get(1).getId());
        assertTrue(ranked.get(0).getScore() > ranked.get(1).getScore());
        assertTrue(ranked.get(0).getScore() > 0D, "打分不能退化成恒 0 —— 那是 S122 遗留②的根因");
    }

    @Test
    void shouldDegradeToIdOrderWhenKeywordAbsent() {
        List<SearchHit> hits = List.of(
                SearchHit.of("3", 0D, Map.of("content", "流程管理")),
                SearchHit.of("1", 0D, Map.of("content", "知识库检索")));

        List<SearchHit> ranked = SearchScorer.rank(hits, "", null);

        assertEquals(2, ranked.size(), "无关键词时语义退化为「按条件取前 topK」，不丢弃任何候选");
        assertEquals("1", ranked.get(0).getId());
        assertEquals("3", ranked.get(1).getId());
        assertEquals(0D, ranked.get(0).getScore(), "无关键词 = 无相关性概念，分数恒 0");
    }

    @Test
    void shouldHonorExplicitFields() {
        SearchHit hit = SearchHit.of("1", 0D, Map.of("title", "报销流程", "content", "知识库检索"));

        double onTitle = SearchScorer.rank(List.of(hit), "报销", List.of("title")).isEmpty()
                ? 0D : SearchScorer.rank(List.of(hit), "报销", List.of("title")).get(0).getScore();
        boolean missedOnContent = SearchScorer.rank(List.of(hit), "报销", List.of("content")).isEmpty();

        assertTrue(onTitle > 0D, "指定字段命中时必须给正分");
        assertTrue(missedOnContent, "指定字段未命中时必须丢弃（字段选择必须真实生效，不能假装用了 fields）");
    }

    @Test
    void shouldConcatenateAllStringValuesWhenFieldsAbsent() {
        // 未指定字段时取全部字符串值，按 key 字典序拼接（Map.of 的迭代顺序不可依赖，必须显式排序）
        assertEquals("A B", SearchScorer.fieldText(Map.of("b", "B", "a", "A"), null));
        assertEquals("B A", SearchScorer.fieldText(Map.of("b", "B", "a", "A"), List.of("b", "a")),
                "显式字段按声明顺序拼接，不是字典序");
    }

    // ==================== 确定性（硬门禁） ====================

    @Test
    void shouldProduceIdenticalResultRegardlessOfInputOrder() {
        List<SearchHit> hits = new ArrayList<>(List.of(
                SearchHit.of("1", 0D, Map.of("content", "知识库检索入门")),
                SearchHit.of("2", 0D, Map.of("content", "知识库")),
                SearchHit.of("3", 0D, Map.of("content", "知识库配置说明")),
                SearchHit.of("4", 0D, Map.of("content", "流程管理"))));

        List<SearchHit> baseline = SearchScorer.rank(hits, "知识库", List.of("content"));

        // 打乱输入顺序：源顺序不稳时（ConcurrentHashMap / HashSet 迭代顺序）也必须给出同一结果
        for (int seed = 0; seed < 8; seed++) {
            List<SearchHit> shuffled = new ArrayList<>(hits);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            List<SearchHit> actual = SearchScorer.rank(shuffled, "知识库", List.of("content"));
            assertEquals(ids(baseline), ids(actual), "输入顺序变化不得改变召回顺序（seed=" + seed + "）");
            for (int i = 0; i < baseline.size(); i++) {
                assertEquals(baseline.get(i).getScore(), actual.get(i).getScore(), 0D,
                        "分数必须逐位可复现（禁止浮点累加顺序漂移），seed=" + seed);
            }
        }
    }

    @Test
    void shouldBreakTieByIdAscending() {
        // 两份内容完全相同的文档必然同分：此时顺序必须落到 id 升序，否则不可复现
        List<SearchHit> hits = List.of(
                SearchHit.of("9", 0D, Map.of("content", "知识库")),
                SearchHit.of("2", 0D, Map.of("content", "知识库")));

        List<SearchHit> ranked = SearchScorer.rank(hits, "知识库", List.of("content"));

        assertEquals(List.of("2", "9"), ids(ranked));
        assertEquals(ranked.get(0).getScore(), ranked.get(1).getScore(), 0D);
    }

    @Test
    void shouldNotModifyInput() {
        SearchHit hit = SearchHit.of("1", 0D, Map.of("content", "知识库"));
        SearchScorer.rank(List.of(hit), "知识库", List.of("content"));
        assertEquals(0D, hit.getScore(), "打分为纯函数，不得就地修改入参");
        assertFalse(hit.getScore() > 0D);
    }

    @Test
    void shouldReturnEmptyOnEmptyInput() {
        assertTrue(SearchScorer.rank(null, "知识库", null).isEmpty());
        assertTrue(SearchScorer.rank(List.of(), "知识库", null).isEmpty());
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::getId).toList();
    }
}

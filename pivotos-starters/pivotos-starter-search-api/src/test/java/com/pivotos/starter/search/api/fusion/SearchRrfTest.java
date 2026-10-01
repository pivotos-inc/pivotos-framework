package com.pivotos.starter.search.api.fusion;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合（S128）。
 * <p>RRF 的价值在于<b>只看名次不看分数</b>，所以「向量相似度（0~1）」与「BM25（无上界）」
 * 这种量纲完全不同的双通道不必先归一化就能合并——这是选它而不选加权求和的原因。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SearchRrfTest {

    @Test
    void shouldFuseTwoChannelsByReciprocalRank() {
        List<SearchRrf.Fused> fused = SearchRrf.fuse(List.of(
                SearchRrf.Channel.of("vector", List.of("a", "b")),
                SearchRrf.Channel.of("fulltext", List.of("b", "c"))));

        assertEquals(3, fused.size());
        // b 两路都命中（1/61 + 1/62）> a（1/61）> c（1/62）
        assertEquals("b", fused.get(0).id(), "两路都命中的候选必须排在只命中一路的前面（这就是 RRF 的增益）");
        assertEquals("a", fused.get(1).id());
        assertEquals("c", fused.get(2).id());
        assertEquals(List.of("vector", "fulltext"), fused.get(0).channels(),
                "命中通道要能回传，便于解释「为什么这条排前面」");
    }

    @Test
    void shouldLetWeightDecideDominantChannel() {
        List<SearchRrf.Channel> channels = List.of(
                SearchRrf.Channel.of("vector", 3.0D, List.of("x")),
                SearchRrf.Channel.of("fulltext", 1.0D, List.of("a")));

        assertEquals("x", SearchRrf.fuse(channels).get(0).id(), "向量权重 3.0 时向量独有候选应居首");

        // 等权时两者同分 → 落到 id 升序的确定性 tie-break（而不是依赖 Map 迭代顺序）
        List<SearchRrf.Channel> equal = List.of(
                SearchRrf.Channel.of("vector", 1.0D, List.of("x")),
                SearchRrf.Channel.of("fulltext", 1.0D, List.of("a")));
        assertEquals("a", SearchRrf.fuse(equal).get(0).id(), "同分必须按 id 升序，保证可复现");
    }

    @Test
    void shouldHonorKMinScoreAndTopK() {
        List<SearchRrf.Channel> channels = List.of(
                SearchRrf.Channel.of("vector", List.of("a", "b", "c")),
                SearchRrf.Channel.of("fulltext", List.of("a")));

        // k 越大，头部名次的权重被削得越平（1/(60+1) → 1/(600+1)）
        double bigK = SearchRrf.fuse(channels, 600, 0D, 0).get(0).score();
        double defaultK = SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0D, 0).get(0).score();
        assertTrue(bigK < defaultK, "k 增大必须削弱头部名次权重");

        // minScore：只在通道尾部擦到的弱候选应被裁掉（a=1/61+1/61≈0.0328 留，b≈0.0161 / c≈0.0159 被裁）
        assertEquals(1, SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0.03D, 0).size(),
                "融合分阈值必须真实生效（默认 0 表示不裁剪）");
        assertEquals(1, SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0.02D, 0).size());
        assertEquals(3, SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0D, 0).size(),
                "默认阈值 0 → 不裁剪");

        assertEquals(2, SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0D, 2).size(), "topK 截断");
        assertEquals(3, SearchRrf.fuse(channels, SearchRrf.DEFAULT_K, 0D, 0).size(), "topK<=0 视为不限");
    }

    @Test
    void shouldCountDuplicateIdOnlyOnce() {
        List<SearchRrf.Fused> fused = SearchRrf.fuse(List.of(
                SearchRrf.Channel.of("vector", List.of("a", "a", "b"))));

        assertEquals(2, fused.size());
        assertEquals("a", fused.get(0).id(), "重复 id 只认首次出现的名次（重复项不该给自己加权）");
    }

    @Test
    void shouldBeDeterministicRegardlessOfChannelOrder() {
        List<SearchRrf.Channel> channels = List.of(
                SearchRrf.Channel.of("vector", 1.2D, List.of("c", "a")),
                SearchRrf.Channel.of("fulltext", 0.8D, List.of("b", "a", "c")));
        List<SearchRrf.Channel> reordered = List.of(
                SearchRrf.Channel.of("fulltext", 0.8D, List.of("b", "a", "c")),
                SearchRrf.Channel.of("vector", 1.2D, List.of("c", "a")));

        List<SearchRrf.Fused> first = SearchRrf.fuse(channels);
        List<SearchRrf.Fused> second = SearchRrf.fuse(reordered);
        assertEquals(first.stream().map(SearchRrf.Fused::id).toList(),
                second.stream().map(SearchRrf.Fused::id).toList(), "通道声明顺序不得改变融合结果");
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).score(), second.get(i).score(), 0D);
        }
    }

    @Test
    void shouldRejectIllegalArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> SearchRrf.fuse(List.of(SearchRrf.Channel.of("v", List.of("a"))), 0, 0D, 0),
                "k 必须 >= 1");
        assertThrows(IllegalArgumentException.class,
                () -> SearchRrf.fuse(List.of(SearchRrf.Channel.of("v", -1D, List.of("a")))),
                "负权重必须拒绝（负权重会让「排在越后面越有利」，语义反了）");
        // 注意：不能用 List.of(null)——它自己就先抛 NPE（List.of 不允许 null 元素）
        assertThrows(IllegalArgumentException.class,
                () -> SearchRrf.fuse(Collections.singletonList(null)));
    }

    @Test
    void shouldReturnEmptyOnNoChannel() {
        assertTrue(SearchRrf.fuse(null).isEmpty());
        assertTrue(SearchRrf.fuse(List.of()).isEmpty());
        assertTrue(SearchRrf.fuse(List.of(SearchRrf.Channel.of("v", List.of()))).isEmpty());
    }

    @Test
    void shouldExposeDocumentedDefaults() {
        assertEquals(60, SearchRrf.DEFAULT_K);
        assertEquals(1.0D, SearchRrf.DEFAULT_WEIGHT, 0D);
        assertEquals(0.0D, SearchRrf.DEFAULT_MIN_SCORE, 0D);
    }
}

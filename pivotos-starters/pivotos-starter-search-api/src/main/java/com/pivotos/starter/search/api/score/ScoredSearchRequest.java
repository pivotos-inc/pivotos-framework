package com.pivotos.starter.search.api.score;

import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.query.SearchCriteria;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 带打分的 Top-K 检索请求（S128「全文召回通道」）。
 * <p>与 {@link com.pivotos.starter.search.api.document.SearchRequest} 的区别：
 * <table>
 *   <tr><th></th><th>SearchRequest（确定性过滤）</th><th>ScoredSearchRequest（打分召回）</th></tr>
 *   <tr><td>语义</td><td>条件成立/不成立，二分位，无相关性概念</td><td>关键词与文档的相关度，连续分数</td></tr>
 *   <tr><td>排序</td><td>由 orders 显式指定</td><td>由得分决定（ES 侧 {@code _score}，simple 侧确定性 BM25）</td></tr>
 *   <tr><td>分页</td><td>pageNum / pageSize</td><td>topK（不分页）</td></tr>
 * </table>
 * 两者<b>并存不互相替代</b>：列表/管理面用前者，RAG 召回用后者。
 *
 * <p><b>Why topK 而非分页</b>：召回阶段的产物是「喂给 LLM 的候选集」，深分页无意义且会让 Fusion 的
 * rank 失真（排到第 200 名的候选即便权重再高也不该影响 RRF）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
public final class ScoredSearchRequest {

    /** 未指定 {@link #candidateWindow} 时的默认候选窗口 */
    public static final int DEFAULT_CANDIDATE_WINDOW = 1000;

    /** 未指定 topK 时的默认召回条数 */
    public static final int DEFAULT_TOP_K = 10;

    /** 索引名 */
    private final String indexName;

    /** 查询词（空串/null 表示无关键词，退化为「按条件取前 topK」） */
    private final String keyword;

    /**
     * 参与打分的字段（空表示「全部字符串字段」）。
     * <p>ES 侧会映射到 {@code multi_match} 的 fields，必须与实际 mapping 的类型匹配——
     * keyword 字段只能整值命中，要做真 BM25 必须打到 text 字段（见 {@code createFullTextIndexIfAbsent}）。
     */
    private final List<String> fields;

    /** 硬性过滤条件（AND 拼接，条件内部 logic 与确定性过滤同口径） */
    private final List<SearchCriteria> criteria;

    /** 返回条数 */
    private final int topK;

    /** 分数阈值：低于该值的命中丢弃 */
    private final double minScore;

    /**
     * 候选窗口：<b>仅在「本地重打分」退化路径上有意义</b>（原生打分的 Provider 直接忽略）。
     * 退化实现先按条件取这么多条候选，再本地 BM25 重排后截断到 topK；
     * 窗口越大召回越全，代价是取更多数据。上限受 {@link LambdaSearchQuery#MAX_PAGE_SIZE} 约束。
     */
    private final int candidateWindow;

    private ScoredSearchRequest(String indexName, String keyword, List<String> fields,
                                List<SearchCriteria> criteria, int topK, double minScore, int candidateWindow) {
        this.indexName = indexName;
        this.keyword = keyword;
        this.fields = fields;
        this.criteria = criteria;
        this.topK = topK;
        this.minScore = minScore;
        this.candidateWindow = candidateWindow;
    }

    /**
     * 最小构造：只给索引名与查询词，其余取默认值。
     */
    public static ScoredSearchRequest of(String indexName, String keyword) {
        return of(indexName, keyword, List.of(), List.of(), DEFAULT_TOP_K, 0D, DEFAULT_CANDIDATE_WINDOW);
    }

    /**
     * 完整构造（含归一化与校验）。
     */
    public static ScoredSearchRequest of(String indexName, String keyword, List<String> fields,
                                         List<SearchCriteria> criteria, int topK, double minScore,
                                         int candidateWindow) {
        String index = indexName == null ? null : indexName.trim();
        if (index == null || index.isEmpty()) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "打分检索的索引名不能为空");
        }
        int effectiveTopK = topK <= 0 ? DEFAULT_TOP_K : topK;
        int effectiveWindow = candidateWindow <= 0 ? DEFAULT_CANDIDATE_WINDOW : candidateWindow;
        // 窗口不得小于 topK（否则「重排后截断」会退化成「先截断再重排」，召回被前端吃掉）
        effectiveWindow = Math.max(effectiveWindow, effectiveTopK);
        effectiveWindow = Math.min(effectiveWindow, LambdaSearchQuery.MAX_PAGE_SIZE);
        effectiveTopK = Math.min(effectiveTopK, LambdaSearchQuery.MAX_PAGE_SIZE);
        return new ScoredSearchRequest(
                index,
                keyword == null ? "" : keyword.trim(),
                fields == null ? List.of() : List.copyOf(fields),
                criteria == null ? new ArrayList<>() : new ArrayList<>(criteria),
                effectiveTopK,
                minScore,
                effectiveWindow);
    }

    /**
     * 是否显式指定了打分字段
     */
    public boolean hasFields() {
        return fields != null && !fields.isEmpty();
    }

    /**
     * 是否有关键词（无关键词时打分为 0，语义退化为「按条件取前 topK」）
     */
    public boolean hasKeyword() {
        return keyword != null && !keyword.isEmpty();
    }
}

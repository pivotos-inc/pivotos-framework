package com.pivotos.starter.search.api.query;

import com.pivotos.starter.search.api.core.LambdaFieldResolver;
import com.pivotos.starter.search.api.enums.SearchErrorCode;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.exception.SearchException;
import com.pivotos.starter.search.api.function.SearchSFunction;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Lambda 链式查询门面。
 *
 * <pre>{@code
 * LambdaSearchQuery<SysOperLog> q = LambdaSearchQuery.of(SysOperLog.class)
 *         .eq(SysOperLog::getStatus, 0)
 *         .like(SysOperLog::getTitle, "登录")
 *         .between(SysOperLog::getOperTime, start, end)
 *         .orderByDesc(SysOperLog::getOperTime)
 *         .page(1, 20);
 * }</pre>
 *
 * <p><b>设计红线</b>：Lambda 只是糖，落库前一律解析为 {@link SearchCriteria} 条件树，
 * 绝不让各 Provider 各自解释 Lambda（同「仲裁不交给 LLM」的确定性原则）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Getter
public final class LambdaSearchQuery<T> {

    /** 单页最大条数（防误传导致深分页拖垮 ES / 内存） */
    public static final int MAX_PAGE_SIZE = 1000;

    /** 实体类型（用于索引名推断与结果反序列化） */
    private final Class<T> entityType;

    /** 显式索引名（为空时按 @SearchIndex 或类名推断） */
    private String indexName;

    private final List<SearchCriteria> criteria = new ArrayList<>();
    private final List<SearchOrder> orders = new ArrayList<>();

    private int pageNum = 1;
    private int pageSize = 20;

    /**
     * 打分召回的查询词（S128）。非空时该查询可交给 {@code SearchTemplate#searchScored}：
     * 有它才有「相关度」概念，为空则退化为「按条件取前 N 条」。
     */
    private String keyword;

    /** 参与打分的字段（空 = 全部字符串字段） */
    private final List<String> fullTextFields = new ArrayList<>();

    /** 召回分数阈值（低于此值丢弃） */
    private double minScore = 0D;

    /**
     * 候选窗口：仅在「本地重打分」退化路径上有意义，见 {@code ScoredSearchRequest#candidateWindow}。
     * 0 表示交给实现自行决定默认值。
     */
    private int candidateWindow = 0;

    private LambdaSearchQuery(Class<T> entityType) {
        this.entityType = entityType;
    }

    public static <T> LambdaSearchQuery<T> of(Class<T> entityType) {
        if (entityType == null) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "实体类型不能为空");
        }
        return new LambdaSearchQuery<>(entityType);
    }

    // ==================== 条件 ====================

    public LambdaSearchQuery<T> eq(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.EQ, value);
    }

    public LambdaSearchQuery<T> ne(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.NE, value);
    }

    public LambdaSearchQuery<T> gt(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.GT, value);
    }

    public LambdaSearchQuery<T> ge(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.GE, value);
    }

    public LambdaSearchQuery<T> lt(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.LT, value);
    }

    public LambdaSearchQuery<T> le(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.LE, value);
    }

    public LambdaSearchQuery<T> like(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.LIKE, value);
    }

    public LambdaSearchQuery<T> likeLeft(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.LIKE_LEFT, value);
    }

    public LambdaSearchQuery<T> likeRight(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.LIKE_RIGHT, value);
    }

    public LambdaSearchQuery<T> match(SearchSFunction<T, ?> field, Object value) {
        return and(field, SearchOp.MATCH, value);
    }

    public LambdaSearchQuery<T> in(SearchSFunction<T, ?> field, Collection<?> values) {
        return and(field, SearchOp.IN, values);
    }

    public LambdaSearchQuery<T> notIn(SearchSFunction<T, ?> field, Collection<?> values) {
        return and(field, SearchOp.NOT_IN, values);
    }

    public LambdaSearchQuery<T> between(SearchSFunction<T, ?> field, Object start, Object end) {
        return and(field, SearchOp.BETWEEN, List.of(start, end));
    }

    public LambdaSearchQuery<T> isNull(SearchSFunction<T, ?> field) {
        return and(field, SearchOp.IS_NULL);
    }

    public LambdaSearchQuery<T> isNotNull(SearchSFunction<T, ?> field) {
        return and(field, SearchOp.IS_NOT_NULL);
    }

    /**
     * OR 连接：与前一个条件取并集
     */
    public LambdaSearchQuery<T> orEq(SearchSFunction<T, ?> field, Object value) {
        return or(field, SearchOp.EQ, value);
    }

    public LambdaSearchQuery<T> orLike(SearchSFunction<T, ?> field, Object value) {
        return or(field, SearchOp.LIKE, value);
    }

    // ==================== 排序 / 分页 ====================

    public LambdaSearchQuery<T> orderByAsc(SearchSFunction<T, ?> field) {
        orders.add(SearchOrder.asc(LambdaFieldResolver.resolve(field)));
        return this;
    }

    public LambdaSearchQuery<T> orderByDesc(SearchSFunction<T, ?> field) {
        orders.add(SearchOrder.desc(LambdaFieldResolver.resolve(field)));
        return this;
    }

    public LambdaSearchQuery<T> page(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "pageNum 必须 >= 1，实际 " + pageNum);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID,
                    "pageSize 必须在 [1, " + MAX_PAGE_SIZE + "]，实际 " + pageSize);
        }
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        return this;
    }

    /**
     * 只取前 N 条（等价 page(1, n)）
     */
    public LambdaSearchQuery<T> limit(int size) {
        return page(1, size);
    }

    // ==================== 打分召回（S128） ====================

    /**
     * 打分召回的查询词。设置了它，该查询就可以交给 {@code SearchTemplate#searchScored} 走相关性通道。
     */
    public LambdaSearchQuery<T> keyword(String keyword) {
        this.keyword = keyword;
        return this;
    }

    /**
     * 参与打分的字段（不指定则用全部字符串字段）。
     * <p>在 ES 实现里它决定 {@code multi_match} 打哪些字段，<b>必须与实际 mapping 的类型一致</b>
     * ——打 keyword 字段只能整值命中，要真 BM25 必须指向 text 字段。
     */
    public LambdaSearchQuery<T> fullTextFields(String... fields) {
        this.fullTextFields.clear();
        if (fields != null) {
            for (String field : fields) {
                if (field != null && !field.isBlank()) {
                    this.fullTextFields.add(field.trim());
                }
            }
        }
        return this;
    }

    /**
     * 召回分数阈值（默认 0，即不裁剪）
     */
    public LambdaSearchQuery<T> minScore(double minScore) {
        this.minScore = minScore;
        return this;
    }

    /**
     * 候选窗口（仅本地重打分退化路径生效，0 = 由实现决定）
     */
    public LambdaSearchQuery<T> candidateWindow(int candidateWindow) {
        this.candidateWindow = candidateWindow;
        return this;
    }

    /** 是否带打分关键词 */
    public boolean hasKeyword() {
        return keyword != null && !keyword.isBlank();
    }

    // ==================== 索引 ====================

    public LambdaSearchQuery<T> index(String indexName) {
        this.indexName = indexName;
        return this;
    }

    // ==================== 内部 ====================

    private LambdaSearchQuery<T> and(SearchSFunction<T, ?> field, SearchOp op, Object... values) {
        return add(field, op, SearchLogic.AND, values);
    }

    private LambdaSearchQuery<T> or(SearchSFunction<T, ?> field, SearchOp op, Object... values) {
        return add(field, op, SearchLogic.OR, values);
    }

    private LambdaSearchQuery<T> add(SearchSFunction<T, ?> field, SearchOp op, SearchLogic logic, Object... values) {
        String name = LambdaFieldResolver.resolve(field);
        List<Object> normalized = normalize(op, values);
        // 首条件无论声明什么逻辑都按 AND 处理（无前驱可连接）
        SearchLogic effective = criteria.isEmpty() ? SearchLogic.AND : logic;
        criteria.add(SearchCriteria.of(name, op, effective, normalized));
        return this;
    }

    /**
     * 值归一化：把「单个集合参数」摊平（IN 传 Collection 时），并做必填校验。
     */
    private List<Object> normalize(SearchOp op, Object[] values) {
        if (op == SearchOp.IS_NULL || op == SearchOp.IS_NOT_NULL) {
            return List.of();
        }
        List<Object> list = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                if (v instanceof Collection<?> coll) {
                    list.addAll(coll);
                } else {
                    list.add(v);
                }
            }
        }
        boolean needValue = op != SearchOp.IS_NULL && op != SearchOp.IS_NOT_NULL;
        if (needValue && list.isEmpty()) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID, "操作符 " + op + " 需要至少一个值");
        }
        if (needValue && list.stream().anyMatch(Objects::isNull)) {
            // 明确拒绝而不是静默丢弃：静默丢弃会让「本想过滤却变成全量」，
            // 判空语义请显式用 isNull()/isNotNull()
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID,
                    "操作符 " + op + " 的值不可为 null，判空请改用 isNull()/isNotNull()");
        }
        if (op == SearchOp.BETWEEN && list.size() != 2) {
            throw new SearchException(SearchErrorCode.QUERY_PARAM_INVALID,
                    "BETWEEN 需要恰好 2 个值，实际 " + list.size());
        }
        return list;
    }
}

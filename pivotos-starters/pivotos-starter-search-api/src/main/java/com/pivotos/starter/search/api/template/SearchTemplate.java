package com.pivotos.starter.search.api.template;

import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;

import java.util.List;

/**
 * 搜索门面（业务侧唯一入口）。
 *
 * <pre>{@code
 * SearchPage<SysOperLog> page = searchTemplate.search(
 *         LambdaSearchQuery.of(SysOperLog.class).like(SysOperLog::getTitle, "登录").page(1, 20));
 * }</pre>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public interface SearchTemplate {

    /**
     * Lambda 链式检索
     */
    <T> SearchPage<T> search(LambdaSearchQuery<T> query);

    /**
     * Lambda 链式<b>打分召回</b>（S128 全文通道）。
     * <p>与 {@link #search(LambdaSearchQuery)} 的关系：<b>并存不替代</b>。
     * 列表/管理面要「条件成立 + 分页」→ 用 {@code search}；RAG 召回要「相关度顺序 + topK」→ 用本方法。
     *
     * <p>用法：
     * <pre>{@code
     * SearchScoredPage<AiKbChunk> page = searchTemplate.searchScored(
     *         LambdaSearchQuery.of(AiKbChunk.class)
     *                 .eq(AiKbChunk::getKbId, kbId)
     *                 .keyword("报销流程")
     *                 .fullTextFields("content")
     *                 .limit(20));
     * }</pre>
     *
     * <p>语义要点：
     * <ol>
     *   <li>命中按<b>分数降序</b>（ES 侧取 {@code _score}，simple 侧取确定性 BM25）；
     *       同分时按文档 id 升序，保证<b>可复现</b>（禁止任何随机/哈希序依赖）；</li>
     *   <li>{@code keyword} 为空则退化为「按条件取前 N 条」，分数恒 0；</li>
     *   <li>{@code topK} 取 {@code LambdaSearchQuery#getPageSize()}（{@code limit(n)} 即 topK=n）；</li>
     *   <li>实现不支持原生打分时（如 easy-es），契约层会退化成「取候选窗口 → 本地确定性重打分」，
     *       <b>行为不会退化成 score 恒 0</b>——那是 S122 遗留②的根因。</li>
     * </ol>
     */
    <T> SearchScoredPage<T> searchScored(LambdaSearchQuery<T> query);

    /**
     * <b>提前</b>按实体类型建好全文映射（S128）。
     * <p>为什么需要它：写入路径（{@link #index}/{@link #indexBatch}）只调 {@code createIndexIfAbsent}，
     * 会把字符串一律落成 keyword；若先写了文档再补 text 映射，ES 会以「keyword 不能改成 text」拒绝
     * （mapper 冲突），全文通道就永远只能整值命中、BM25 打不出来。
     * <b>故凡是要走打分召回的实体，必须在首次写入之前调用本方法</b>。
     *
     * @param entityType 实体类型（索引名由其解析）
     * @param fields     需要被建成 text 的字段（其余字符串仍是 keyword）
     */
    <T> void createFullTextIndexIfAbsent(Class<T> entityType, String... fields);

    /**
     * Lambda 链式计数
     */
    <T> long count(LambdaSearchQuery<T> query);

    /**
     * 索引单个实体（索引名取 @SearchIndex 或类名推断）
     */
    <T> void index(T entity);

    /**
     * 批量索引
     */
    <T> void indexBatch(List<T> entities);

    /**
     * 按索引名 + 文档 ID 删除
     */
    void delete(String indexName, String id);

    /**
     * 当前生效的实现类型（便于日志与运维核对）
     */
    SearchProviderType type();
}

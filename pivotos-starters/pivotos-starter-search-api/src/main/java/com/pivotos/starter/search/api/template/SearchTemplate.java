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

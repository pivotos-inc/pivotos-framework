package com.pivotos.starter.search.api.spi;

import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchProviderType;

import java.util.List;

/**
 * 搜索实现 SPI。
 * <p>与 {@code KbVectorStoreProvider} 同口径：主 Starter 收集全部 Provider Bean，按
 * {@code pivotos.search.type} 路由，未命中则回落到 {@link SearchProviderType#SIMPLE} 并打 WARN
 * ——保证「配错也能起服」（vector-store milvus/simple 的既有经验）。
 *
 * <p><b>实现纪律</b>：Provider 不做实体映射，只处理「索引 + 扁平文档 + 条件树」。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public interface SearchProvider {

    /**
     * 实现类型（与 {@code pivotos.search.type} 对应）
     */
    SearchProviderType type();

    /**
     * 索引写入（同 id 覆盖）
     */
    void index(SearchDocument document);

    /**
     * 批量索引写入
     */
    void indexBatch(List<SearchDocument> documents);

    /**
     * 按 ID 删除文档
     */
    void delete(String indexName, String id);

    /**
     * 检索
     */
    SearchResult search(SearchRequest request);

    /**
     * 计数（与 search 同条件，不受分页影响）
     */
    long count(SearchRequest request);

    /**
     * 索引是否存在
     */
    boolean existsIndex(String indexName);

    /**
     * 索引不存在则创建（simple 实现为惰性建桶）
     */
    void createIndexIfAbsent(String indexName);
}

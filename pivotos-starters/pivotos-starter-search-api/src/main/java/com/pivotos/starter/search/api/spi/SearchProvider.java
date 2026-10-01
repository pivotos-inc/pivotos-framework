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

    /**
     * 该实现<b>当前是否可用</b>。默认 true（simple 等本地实现恒可用）。
     * <p>远端实现（easy-es / es-java）在启动期做一次服务端探测：ES 不可达、服务端版本不在支持区间、
     * 或兼容头与服务端版本冲突时返回 false。路由工厂据此<b>回落到 simple 并打 WARN</b>，
     * 而不是让应用起不来或让每次检索都抛异常——与「配错也能起服」的既有口径一致。
     * <p>注意：探测结果在启动期缓存，运行期不会反复发起；ES 后来恢复时需要重启应用重新探测。
     */
    default boolean isAvailable() {
        return true;
    }
}

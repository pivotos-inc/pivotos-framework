package com.pivotos.starter.search.api.spi;

import com.pivotos.starter.search.api.document.SearchDocument;
import com.pivotos.starter.search.api.document.SearchHit;
import com.pivotos.starter.search.api.document.SearchRequest;
import com.pivotos.starter.search.api.document.SearchResult;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.score.ScoredSearchRequest;
import com.pivotos.starter.search.api.score.SearchScorer;

import java.util.ArrayList;
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
     * 带打分的 Top-K 召回通道（S128）。
     * <p>与 {@link #search(SearchRequest)} 的关系：<b>并存不替代</b>。
     * 列表/管理面要的是「条件成立与否 + 分页」，走 {@code search}；RAG 召回要的是「相关度顺序 + topK」，
     * 走本方法。两者共用同一份索引与同一套条件表达（{@code SearchCriteria}），不新增平行数据结构。
     *
     * <p><b>为什么必须是 default 方法</b>：本 SPI 已有 simple / easy-es / es-java 三个实现，
     * 若新增抽象方法会让它们全部编译失败；给默认实现则「未适配的实现自动降级为本地确定性重打分」，
     * 既不破坏既有代码，也<b>不会退化成 score 恒 0</b>（那是 S122 遗留②的根因）。
     *
     * <p>默认实现策略：先按条件取 {@code candidateWindow} 条候选 → {@link SearchScorer} 本地 BM25 重排
     * → 丢掉低于 {@code minScore} 的 → 截断到 topK。原生支持打分的 Provider（simple / es-java）
     * 覆写本方法以取得<b>引擎侧真 BM25</b>，避免把全量候选拉回本地。
     */
    default SearchResult searchScored(ScoredSearchRequest request) {
        ScoredSearchRequest req = ScoredSearchRequest.of(request == null ? null : request.getIndexName(),
                request == null ? null : request.getKeyword(),
                request == null ? null : request.getFields(),
                request == null ? null : request.getCriteria(),
                request == null ? 0 : request.getTopK(),
                request == null ? 0D : request.getMinScore(),
                request == null ? 0 : request.getCandidateWindow());
        SearchResult candidates = search(SearchRequest.of(req.getIndexName(), req.getCriteria(),
                List.of(), 1, req.getCandidateWindow()));
        List<SearchHit> scored = SearchScorer.rank(candidates.getHits(), req.getKeyword(), req.getFields());
        List<SearchHit> kept = new ArrayList<>(scored.size());
        for (SearchHit hit : scored) {
            if (hit.getScore() >= req.getMinScore()) {
                kept.add(hit);
            }
        }
        List<SearchHit> top = kept.size() <= req.getTopK() ? kept : new ArrayList<>(kept.subList(0, req.getTopK()));
        return SearchResult.of(top, kept.size());
    }

    /**
     * 全文通道专用建索引（不存在时）：保证 {@code fields} 落到可被 BM25 打分的字段类型上。
     * <p><b>为什么不直接复用 createIndexIfAbsent</b>：{@code createIndexIfAbsent} 刻意把所有字符串
     * 落成 keyword（S122 缺陷①的修法），而 keyword 字段只有「整值相等」一种命中形态，
     * 打不了 BM25——全文通道必须有一份 span text 字段。故单列一个方法，<b>默认实现就是原方法</b>，
     * 只有真正的搜索引擎实现才需要覆写。
     *
     * @param indexName 索引名
     * @param fields    需要按全文（text）建映射的字段
     */
    default void createFullTextIndexIfAbsent(String indexName, List<String> fields) {
        createIndexIfAbsent(indexName);
    }

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

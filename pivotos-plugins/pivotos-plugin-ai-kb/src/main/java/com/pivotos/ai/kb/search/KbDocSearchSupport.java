package com.pivotos.ai.kb.search;

import com.pivotos.ai.kb.config.KbDocSearchProperties;
import com.pivotos.ai.kb.domain.dto.KbDocPageQuery;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.api.template.SearchTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档列表检索支撑（S122）：只接管「文档元数据 + 分页过滤」这一支，
 * RAG 召回（向量 + BM25 + RRF 融合）仍由 {@code KbPipelineService} 承担，两者互不干扰。
 *
 * <p><b>租户隔离必须显式补</b>：DB 路径下租户过滤由 MP 的 {@code TenantLineInnerInterceptor}
 * 在 SQL 解析期自动追加；走检索后没有拦截器，若不显式加 {@code tenant_id} 条件就会跨租户泄漏。
 * 口径与 {@code ColumnTenantStrategy} 对齐：<b>无租户上下文时放行（单租户语义）</b>，有则按值过滤。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class KbDocSearchSupport {

    private static final Logger log = LoggerFactory.getLogger(KbDocSearchSupport.class);

    /** 索引名：与 {@link SearchIndexNameResolver} 对 {@code KbDocument} 的推断结果保持一致 */
    public static final String INDEX_NAME = SearchIndexNameResolver.resolve(KbDocument.class);

    private final ObjectProvider<SearchTemplate> templateProvider;
    private final KbDocSearchProperties properties;

    public KbDocSearchSupport(ObjectProvider<SearchTemplate> templateProvider,
                              KbDocSearchProperties properties) {
        this.templateProvider = templateProvider;
        this.properties = properties == null ? new KbDocSearchProperties() : properties;
    }

    public boolean enabled() {
        return properties.isEnabled() && templateProvider.getIfAvailable() != null;
    }

    public void index(KbDocument entity) {
        if (!enabled() || entity == null || entity.getId() == null) {
            return;
        }
        try {
            template().index(entity);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档写索引失败（不影响业务）：id={}, reason={}",
                    entity.getId(), e.getMessage());
        }
    }

    public void indexBatch(List<KbDocument> entities) {
        if (!enabled() || entities == null || entities.isEmpty()) {
            return;
        }
        List<KbDocument> valid = new ArrayList<>(entities.size());
        for (KbDocument entity : entities) {
            if (entity != null && entity.getId() != null) {
                valid.add(entity);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        try {
            template().indexBatch(valid);
            log.info("[PivotOS][search] 知识库文档索引回灌 {} 条（索引 {}）", valid.size(), INDEX_NAME);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档索引批量回灌失败（不影响业务）：size={}, reason={}",
                    valid.size(), e.getMessage());
        }
    }

    public void deleteById(Long id) {
        if (!enabled() || id == null) {
            return;
        }
        try {
            template().delete(INDEX_NAME, String.valueOf(id));
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档删索引失败（不影响业务）：id={}, reason={}", id, e.getMessage());
        }
    }

    /**
     * 文档列表检索。
     *
     * @return 命中分页；<b>null 表示检索不可用</b>，调用方回退 DB 查询
     */
    public SearchPage<KbDocument> search(KbDocPageQuery query) {
        if (!enabled() || query == null) {
            return null;
        }
        try {
            LambdaSearchQuery<KbDocument> lambda = LambdaSearchQuery.of(KbDocument.class);
            // 租户隔离：无上下文放行（与 MP 拦截器的单租户语义一致），有上下文按值过滤
            Long tenantId = TenantContext.get();
            if (tenantId != null) {
                lambda.eq(KbDocument::getTenantId, tenantId);
            }
            if (query.getKbId() != null) {
                lambda.eq(KbDocument::getKbId, query.getKbId());
            }
            if (StringUtils.hasText(query.getFileName())) {
                lambda.like(KbDocument::getFileName, query.getFileName().trim());
            }
            if (query.getStatus() != null) {
                lambda.eq(KbDocument::getStatus, query.getStatus());
            }
            lambda.orderByDesc(KbDocument::getCreateTime);
            lambda.page(query.getPageNum(), query.getPageSize());
            return template().search(lambda);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档检索失败，回退数据库查询：reason={}", e.getMessage());
            return null;
        }
    }

    /**
     * @return 索引内文档数；-1 表示不可用
     */
    public long countIndexed() {
        if (!enabled()) {
            return -1L;
        }
        try {
            return template().count(LambdaSearchQuery.of(KbDocument.class).page(1, 1));
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档索引计数失败：reason={}", e.getMessage());
            return -1L;
        }
    }

    public int getBootstrapMaxRows() {
        return properties.getBootstrapMaxRows();
    }

    public boolean isBootstrapOnStart() {
        return properties.isBootstrapOnStart();
    }

    private SearchTemplate template() {
        return templateProvider.getObject();
    }
}

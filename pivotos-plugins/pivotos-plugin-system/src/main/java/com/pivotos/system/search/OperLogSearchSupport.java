package com.pivotos.system.search;

import com.pivotos.starter.search.api.core.SearchIndexNameResolver;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.system.config.OperLogSearchProperties;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 操作日志检索支撑（S122）：把 {@code SysOperLog} 的查询与索引维护收敛到一处，
 * {@code OperLogServiceImpl} 只决定「走搜索还是走 DB」。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *     <li>索引写入失败<b>只 WARN 不抛出</b>——检索是旁路，绝不能拖垮业务写；</li>
 *     <li>检索失败<b>返回 null</b>，由调用方回退 DB 查询——检索故障不能让日志页白屏；</li>
 *     <li>Starter 缺失（{@code ObjectProvider} 拿不到 Bean）时全部能力静默降级。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class OperLogSearchSupport {

    private static final Logger log = LoggerFactory.getLogger(OperLogSearchSupport.class);

    /** 索引名：与 {@link SearchIndexNameResolver} 对 {@code SysOperLog} 的推断结果保持一致 */
    public static final String INDEX_NAME = SearchIndexNameResolver.resolve(SysOperLog.class);

    private final ObjectProvider<SearchTemplate> templateProvider;
    private final OperLogSearchProperties properties;

    public OperLogSearchSupport(ObjectProvider<SearchTemplate> templateProvider,
                                OperLogSearchProperties properties) {
        this.templateProvider = templateProvider;
        this.properties = properties == null ? new OperLogSearchProperties() : properties;
    }

    /**
     * 检索是否可用：开关开启且 Starter 已装配。
     */
    public boolean enabled() {
        return properties.isEnabled() && templateProvider.getIfAvailable() != null;
    }

    /**
     * 写索引（保存/更新后调用）。失败仅 WARN。
     */
    public void index(SysOperLog entity) {
        if (!enabled() || entity == null || entity.getId() == null) {
            return;
        }
        try {
            template().index(entity);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志写索引失败（不影响业务）：id={}, reason={}",
                    entity.getId(), e.getMessage());
        }
    }

    /**
     * 批量写索引（冷启动回灌用）。失败仅 WARN。
     */
    public void indexBatch(List<SysOperLog> entities) {
        if (!enabled() || entities == null || entities.isEmpty()) {
            return;
        }
        List<SysOperLog> valid = new ArrayList<>(entities.size());
        for (SysOperLog entity : entities) {
            if (entity != null && entity.getId() != null) {
                valid.add(entity);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        try {
            template().indexBatch(valid);
            log.info("[PivotOS][search] 操作日志索引回灌 {} 条（索引 {}）", valid.size(), INDEX_NAME);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志索引批量回灌失败（不影响业务）：size={}, reason={}",
                    valid.size(), e.getMessage());
        }
    }

    /**
     * 按主键删索引（日志清理任务用）。失败仅 WARN。
     */
    public void deleteByIds(Collection<Long> ids) {
        if (!enabled() || ids == null || ids.isEmpty()) {
            return;
        }
        try {
            for (Long id : ids) {
                if (id != null) {
                    template().delete(INDEX_NAME, String.valueOf(id));
                }
            }
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志删索引失败（不影响业务）：size={}, reason={}",
                    ids.size(), e.getMessage());
        }
    }

    /**
     * 检索。
     *
     * @return 命中分页结果；<b>null 表示检索不可用</b>（未开启 / Starter 缺失 / 检索异常），调用方应回退 DB 查询
     */
    public SearchPage<SysOperLog> search(OperLogQuery query) {
        if (!enabled() || query == null) {
            return null;
        }
        try {
            LambdaSearchQuery<SysOperLog> lambda = LambdaSearchQuery.of(SysOperLog.class);
            if (StringUtils.hasText(query.getModule())) {
                lambda.like(SysOperLog::getModule, query.getModule().trim());
            }
            if (StringUtils.hasText(query.getOperType())) {
                lambda.eq(SysOperLog::getOperType, query.getOperType().trim());
            }
            if (StringUtils.hasText(query.getOperName())) {
                lambda.like(SysOperLog::getOperName, query.getOperName().trim());
            }
            if (query.getStatus() != null) {
                lambda.eq(SysOperLog::getStatus, query.getStatus());
            }
            LocalDateTime begin = query.getBeginTime();
            LocalDateTime end = query.getEndTime();
            if (begin != null && end != null) {
                lambda.between(SysOperLog::getOperTime, begin, end);
            } else if (begin != null) {
                lambda.ge(SysOperLog::getOperTime, begin);
            } else if (end != null) {
                lambda.le(SysOperLog::getOperTime, end);
            }
            lambda.orderByDesc(SysOperLog::getOperTime);
            lambda.page(query.getPageNum(), query.getPageSize());
            return template().search(lambda);
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志检索失败，回退数据库查询：reason={}", e.getMessage());
            return null;
        }
    }

    /**
     * 索引现有文档数（用于判断是否已回灌）。
     *
     * @return 文档数；-1 表示不可用（调用方据此跳过回灌）
     */
    public long countIndexed() {
        if (!enabled()) {
            return -1L;
        }
        try {
            return template().count(LambdaSearchQuery.of(SysOperLog.class).page(1, 1));
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志索引计数失败：reason={}", e.getMessage());
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

package com.pivotos.ai.kb.search;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库文档索引冷启动回灌（S122）：仅在索引为空时从数据库回灌一次。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class KbDocIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KbDocIndexBootstrap.class);

    private final KbDocumentMapper documentMapper;
    private final KbDocSearchSupport support;

    public KbDocIndexBootstrap(KbDocumentMapper documentMapper, KbDocSearchSupport support) {
        this.documentMapper = documentMapper;
        this.support = support;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // 回灌是旁路：任何异常都不能把应用拖到起不来（起服失败比「检索少数据」严重得多）
        try {
            doBootstrap();
        } catch (Exception e) {
            log.warn("[PivotOS][search] 知识库文档索引回灌失败（不影响起服）：reason={}", e.getMessage());
        }
    }

    private void doBootstrap() {
        if (!support.enabled() || !support.isBootstrapOnStart()) {
            return;
        }
        long indexed = support.countIndexed();
        if (indexed > 0) {
            log.info("[PivotOS][search] 知识库文档索引已有 {} 条，跳过回灌", indexed);
            return;
        }
        if (indexed < 0) {
            log.warn("[PivotOS][search] 知识库文档索引计数不可用，跳过回灌");
            return;
        }
        int maxRows = Math.max(support.getBootstrapMaxRows(), 1);
        long total = documentMapper.selectCount(Wrappers.<KbDocument>lambdaQuery());
        if (total > maxRows) {
            log.warn("[PivotOS][search] 知识库文档 {} 条超出回灌上限 {}，本次仅回灌最近 {} 条",
                    total, maxRows, maxRows);
        }
        Page<KbDocument> page = documentMapper.selectPage(new Page<>(1, maxRows),
                Wrappers.<KbDocument>lambdaQuery().orderByDesc(KbDocument::getCreateTime));
        List<KbDocument> records = page == null ? null : page.getRecords();
        if (records == null || records.isEmpty()) {
            log.info("[PivotOS][search] 知识库文档无历史数据，索引回灌跳过");
            return;
        }
        support.indexBatch(records);
    }
}

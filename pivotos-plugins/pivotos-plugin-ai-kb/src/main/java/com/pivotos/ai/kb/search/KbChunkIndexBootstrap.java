package com.pivotos.ai.kb.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文本块全文索引冷启动回灌（S128）：仅在索引为空时从 {@code ai_kb_chunk} 灌一次。
 * <p>为什么必须有它：<b>simple 实现重启即失</b>。若不做回灌，冷启动后全文通道恒空，
 * 混合检索会静默退化成「只有向量一路」——用户看到的是「换了 ES 之后反而搜不准」，
 * 而不是一条明确的报错。故回灌与 {@code KbDocIndexBootstrap} 同口径，且<b>异常只 WARN 不起服失败</b>。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class KbChunkIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KbChunkIndexBootstrap.class);

    private final KbChunkSearchSupport support;

    public KbChunkIndexBootstrap(KbChunkSearchSupport support) {
        this.support = support;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            int filled = support.bootstrapIfEmpty();
            if (filled > 0) {
                log.info("[PivotOS][search] 文本块全文索引回灌完成：{} 条", filled);
            }
        } catch (Exception e) {
            // 回灌是旁路：任何异常都不能把应用拖到起不来（起服失败比「召回少一路」严重得多）
            log.warn("[PivotOS][search] 文本块索引回灌失败（不影响起服）：reason={}", e.getMessage());
        }
    }
}

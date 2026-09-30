package com.pivotos.ai.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库文档列表检索开关（S122）。
 * <p>默认开启：{@code KbDocumentServiceImpl#page} 走 SearchTemplate；关闭即回到 MyBatis-Plus 查询。
 * 注意：本开关只覆盖<b>文档元数据列表</b>检索，RAG 召回（向量 + BM25 + RRF）不受影响。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@ConfigurationProperties(prefix = "pivotos.search.kb-doc")
public class KbDocSearchProperties {

    /** 是否走搜索抽象（false → 回退 DB 查询） */
    private boolean enabled = true;

    /** 起服时若索引为空，是否从数据库全量回灌 */
    private boolean bootstrapOnStart = true;

    /** 单次回灌上限 */
    private int bootstrapMaxRows = 20000;
}

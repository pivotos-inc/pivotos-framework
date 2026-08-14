package com.pivotos.ai.kb.api.dto;

import lombok.Data;

/**
 * 知识库规模统计 DTO（跨 Plugin 契约，运营工作台/数据大屏用，S71）。
 */
@Data
public class KbStatsDTO {

    /** 知识库总数 */
    private Long baseCount;

    /** 文档总数 */
    private Long documentCount;

    /** 分块总数（按文档 chunk_count 汇总） */
    private Long chunkCount;

    /** 评测跑分记录总数 */
    private Long evalRecordCount;
}

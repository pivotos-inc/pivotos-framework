package com.pivotos.mind.api.vo;

import lombok.Data;

/** 智域首页统计 */
@Data
public class MindStatsVO {

    /** 知识库数量 */
    private Long knowledgeCount;

    /** 待办总数 */
    private Long todoTotal;

    /** 未完成待办数 */
    private Long todoPending;
}

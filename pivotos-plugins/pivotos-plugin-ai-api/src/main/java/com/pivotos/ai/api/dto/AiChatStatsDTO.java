package com.pivotos.ai.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 运营统计 DTO（跨 Plugin 契约，运营工作台/数据大屏用，S71）。
 */
@Data
public class AiChatStatsDTO {

    /** 会话总数 */
    private Long conversationCount;

    /** 消息总数 */
    private Long messageCount;

    /** 供应商总数 */
    private Long providerCount;

    /** 启用中的 API Key 数（status=0） */
    private Long activeKeyCount;

    /** 启用中但近期有失败记录的 Key 数（failCount>0） */
    private Long unhealthyKeyCount;

    /** 近 N 日每日消息趋势（缺日补 0） */
    private List<AiTrendPointDTO> messageTrend = new ArrayList<>();
}

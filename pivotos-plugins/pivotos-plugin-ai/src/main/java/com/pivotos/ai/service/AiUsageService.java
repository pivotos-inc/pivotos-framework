package com.pivotos.ai.service;

import com.pivotos.ai.domain.vo.AiUsageProviderVO;
import com.pivotos.ai.domain.vo.AiUsageSummaryVO;
import com.pivotos.ai.domain.vo.AiUsageUserVO;

import java.util.List;

/**
 * AI Token 用量统计服务（S92）
 */
public interface AiUsageService {

    /**
     * 汇总：总量 + 场景分布 + 近 days 日趋势
     *
     * @param days 统计窗口天数（1~90，越界收敛）
     */
    AiUsageSummaryVO summary(int days);

    /** 按供应商 × Key 聚合（token 总量倒序） */
    List<AiUsageProviderVO> byProvider(int days);

    /** 按用户聚合（token 总量倒序） */
    List<AiUsageUserVO> byUser(int days);
}

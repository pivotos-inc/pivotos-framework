package com.pivotos.ai.service;

import com.pivotos.ai.orchestrator.PlanRunResult;
import com.pivotos.ai.orchestrator.ToolPlan;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.ai.domain.dto.AiToolPlanQuery;
import com.pivotos.ai.domain.vo.AiToolPlanVO;

/**
 * AI 工具多步编排服务（A5-1 / S116）。
 */
public interface AiOrchestratorService {

    /**
     * 由意图生成计划草案（不执行任何工具）。
     *
     * @param intent 用户意图
     * @return 计划 VO（含步骤明细与 AI 原文摘要）
     */
    AiToolPlanVO draft(String intent);

    /**
     * 按计划 ID 执行（并校验写确认授权）。
     *
     * @param planId    计划 ID
     * @param confirmed 是否已获得用户对写操作的二次确认
     * @return 执行结果 VO
     */
    AiToolPlanVO run(Long planId, boolean confirmed);

    /**
     * 由意图生成计划并立即执行；含写步骤且未确认时停在写步骤前。
     *
     * @param intent    用户意图
     * @param confirmed 是否已获得写操作二次确认
     * @return 执行结果 VO
     */
    AiToolPlanVO draftAndRun(String intent, boolean confirmed);

    /** 编排记录分页查询 */
    PageResult<AiToolPlanVO> page(AiToolPlanQuery query);

    /** 编排明细 */
    AiToolPlanVO detail(Long id);

    /** 当前可见的活工具目录（供 PC 页展示「编排能用到哪些工具」） */
    java.util.List<String> availableTools();

    /** 内部：计划 record 取用，供上层组合调用 */
    ToolPlan planOf(Long id);

    /** 内部：执行入口返回原始结果，便于 IT 断言 */
    PlanRunResult execute(ToolPlan plan, Long planId, boolean confirmed, long startedAt);
}

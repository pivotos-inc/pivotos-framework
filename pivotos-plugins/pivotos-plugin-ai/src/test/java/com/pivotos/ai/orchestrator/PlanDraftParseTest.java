package com.pivotos.ai.orchestrator;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计划原文解析用例（S120 前置修复 O-1）。
 *
 * <p>背景（S119 K1）：LLM 会把跨步引用写成<b>不带引号的裸值</b>
 * {@code "instanceId": ${step1.list[0].instanceId}} → 整段不是合法 JSON →
 * {@code 5063 规划器未产出可解析的调用链}。「催办类」意图实测 24 次尝试 23 次失败，
 * 同一服务上另两个意图稳定成功——不是偶发抖动，是当前模型下的高概率确定性失败。
 * 修法：解析前对「不在字符串字面量内的 ${...}」做确定性归一（O-1 的 O1），
 * 并在 prompt 里明确要求占位符必须带双引号（O-1 的 O2）。
 *
 * <p>本用例把那 23 次失败的原文形态原样钉住，防止归一逻辑被后续重构悄悄摘掉。
 */
class PlanDraftParseTest {

    private final PlanDraftService service = new PlanDraftService(null, null);

    /** S119 实证里 23/24 失败的原文形态（逐字照抄后端日志，裸值占位符） */
    private static final String RAW_UNQUOTED_REF = "{\n"
            + "  \"goal\": \"催办当前用户最近发起的第一条未完成的流程实例\",\n"
            + "  \"steps\": [\n"
            + "    {\"no\": 1, \"tool\": \"queryMyFlowInstances\", "
            + "\"args\": {\"pageNum\": 1, \"pageSize\": 1, \"confirm\": false}, "
            + "\"reason\": \"查询用户发起的最新一条流程实例\"},\n"
            + "    {\"no\": 2, \"tool\": \"urgeFlowInstance\", "
            + "\"args\": {\"instanceId\": ${step1.list[0].instanceId}, \"confirm\": false}, "
            + "\"reason\": \"对查到的最新流程实例执行催办\"}\n"
            + "  ],\n"
            + "  \"unmapped\": \"\"\n"
            + "}";

    @Test
    @DisplayName("裸值引用占位符：解析归一后成功，且引用原样保留（S119 23/24 失败的原文形态）")
    void unquotedRefIsNormalized() {
        ToolPlan plan = service.parsePlan(RAW_UNQUOTED_REF);
        Assertions.assertEquals(2, plan.steps().size());
        Assertions.assertEquals("催办当前用户最近发起的第一条未完成的流程实例", plan.goal());
        Assertions.assertEquals("${step1.list[0].instanceId}",
                plan.steps().get(1).args().get("instanceId"));
    }

    @Test
    @DisplayName("已带引号的引用：不做二次加工（值不变、不出现双引号套双引号）")
    void quotedRefUntouched() {
        String raw = RAW_UNQUOTED_REF.replace("${step1.list[0].instanceId}",
                "\"${step1.list[0].instanceId}\"");
        ToolPlan plan = service.parsePlan(raw);
        Assertions.assertEquals("${step1.list[0].instanceId}",
                plan.steps().get(1).args().get("instanceId"));
    }

    @Test
    @DisplayName("字符串内拼接的引用：位于字符串字面量内，禁止补引号（否则会把合法文本改坏）")
    void refInsideStringKept() {
        String raw = "{\"goal\":\"g\",\"steps\":[{\"no\":1,\"tool\":\"sendInboxMessage\","
                + "\"args\":{\"content\":\"我当前有 ${step1} 项待办任务。\",\"confirm\":false},"
                + "\"reason\":\"r\"}],\"unmapped\":\"\"}";
        ToolPlan plan = service.parsePlan(raw);
        Assertions.assertEquals("我当前有 ${step1} 项待办任务。",
                plan.steps().get(0).args().get("content"));
        Assertions.assertEquals(raw, PlanDraftService.normalizeUnquotedPlaceholders(raw));
    }

    @Test
    @DisplayName("数组内裸值引用：一并归一成字符串元素")
    void bareRefInArray() {
        String raw = "{\"goal\":\"g\",\"steps\":[{\"no\":1,\"tool\":\"t\","
                + "\"args\":{\"ids\":[${step1},${step2}]},\"reason\":\"r\"}],\"unmapped\":\"\"}";
        ToolPlan plan = service.parsePlan(raw);
        Assertions.assertEquals(java.util.List.of("${step1}", "${step2}"),
                plan.steps().get(0).args().get("ids"));
    }

    @Test
    @DisplayName("Markdown 围栏 + 裸值引用：先去围栏再归一")
    void fenceWithUnquotedRef() {
        ToolPlan plan = service.parsePlan("```json\n" + RAW_UNQUOTED_REF + "\n```");
        Assertions.assertEquals(2, plan.steps().size());
    }

    @Test
    @DisplayName("无引用的正常 JSON：原文逐字不变（归一必须零副作用）")
    void plainJsonUntouched() {
        String raw = "{\"goal\":\"g\",\"steps\":[{\"no\":1,\"tool\":\"queryMyPendingTaskCount\","
                + "\"args\":{\"confirm\":false},\"reason\":\"r\"}],\"unmapped\":\"\"}";
        Assertions.assertEquals(raw, PlanDraftService.normalizeUnquotedPlaceholders(raw));
        Assertions.assertEquals(1, service.parsePlan(raw).steps().size());
    }

    @Test
    @DisplayName("负例：归一不是万能——压根不是 JSON 时仍按 5063 明确失败，不静默填空计划")
    void nonJsonStillFails() {
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> service.parsePlan("抱歉，这个意图我无法完成"));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_PLAN_EMPTY.getCode(), ex.getCode());
    }
}

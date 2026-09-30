package com.pivotos.ai.orchestrator;

import com.pivotos.ai.service.AiToolService;
import com.pivotos.ai.tool.ToolGuardSignal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排执行器用例（A5-1 / S116）——写闸拦停、失败即停、引用传递、逐步审计关联。
 *
 * <p>工具侧用 Mockito 模拟 {@link AiToolService}：本层的职责是「编排语义」，
 * 工具自身的守卫由 {@code GuardedToolCallback} 负责（其用例在既有类里）。
 */
class PlanExecutorTest {

    private AiToolService toolService;
    private Map<String, ToolSpec> specs;
    private PlanExecutor executor;

    @BeforeEach
    void setUp() {
        toolService = Mockito.mock(AiToolService.class);
        specs = new LinkedHashMap<>();
        specs.put("queryMyFlowInstances", new ToolSpec("queryMyFlowInstances", "查询实例",
                Map.of("pageNum", "integer", "pageSize", "integer", "confirm", "boolean"),
                List.of("pageNum", "pageSize", "confirm"), false));
        specs.put("urgeFlowInstance", new ToolSpec("urgeFlowInstance", "催办实例",
                Map.of("instanceId", "integer", "confirm", "boolean"),
                List.of("instanceId", "confirm"), true));
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setMaxSteps(10);
        executor = new PlanExecutor(toolService, new ToolPlanValidator(properties), properties);
    }

    private static final String INSTANCE_JSON = "{\"total\":1,\"pageNum\":1,\"list\":["
            + "{\"instanceId\":1900000000000001,\"flowName\":\"网关上线\"}]}";

    private ToolPlan readPlan() {
        return new ToolPlan("查我发起的实例", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", 1, "pageSize", 10, "confirm", false), "取列表")
        ), "");
    }

    private ToolPlan readWritePlan() {
        return new ToolPlan("催办第一条", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", 1, "pageSize", 10, "confirm", false), "取列表"),
                new PlanStep(2, "urgeFlowInstance",
                        Map.of("instanceId", "${step1.list[0].instanceId}", "confirm", false), "催办第一条")
        ), "");
    }

    @Test
    @DisplayName("纯只读链：确认与否都会跑完，状态 success")
    void readonlyChainSucceedsWithoutConfirm() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        PlanRunResult result = executor.execute(readPlan(), specs, 100L, false, System.nanoTime());
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(1, result.executedSteps());
        Assertions.assertEquals(0, result.blockedStep());
    }

    @Test
    @DisplayName("写步骤未确认：停在首个写步骤，已执行步骤结果保留，写工具确实没有被调用")
    void writeStepBlockedUntilConfirmed() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        PlanRunResult result = executor.execute(readWritePlan(), specs, 101L, false, System.nanoTime());
        Assertions.assertTrue(result.needConfirm());
        Assertions.assertEquals(1, result.executedSteps());
        Assertions.assertEquals(2, result.blockedStep());
        Assertions.assertTrue(result.outputs().containsKey(1));
        Mockito.verify(toolService, Mockito.never()).invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString());
    }

    @Test
    @DisplayName("写步骤已确认：从中断点继续，引用被渲染成前序输出的真实值，且注入 confirm=true")
    void writeStepRunsAfterConfirm() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        Mockito.when(toolService.invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString()))
                .thenReturn("催办通知已发送");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 102L, true, System.nanoTime());
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(2, result.executedSteps());

        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        Mockito.verify(toolService).invokeTool(Mockito.eq("urgeFlowInstance"), args.capture());
        // 关键点一：instanceId 必须是渲染后的真实值，且保持数字类型（不是字符串 "1900000000000001"）
        Assertions.assertTrue(args.getValue().contains("1900000000000001"));
        Assertions.assertFalse(args.getValue().contains("${step1"));
        // 关键点二：用户已确认时，写工具的 confirm 由编排层注入 true（否则会被守卫预检拦回）
        Assertions.assertTrue(args.getValue().contains("\"confirm\":true"));
    }

    @Test
    @DisplayName("写步骤未确认时：传下去的 confirm 必须保持 false（不得以任何形式偷偷放行）")
    void writeStepKeepsConfirmFalseWhenNotConfirmed() {
        // 场景守卫可能失效（工具改为只读标记），此时不得因为编排层逻辑误注入 confirm
        specs.put("urgeFlowInstance", new ToolSpec("urgeFlowInstance", "催办实例",
                Map.of("instanceId", "integer", "confirm", "boolean"), List.of("instanceId", "confirm"), false));
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        Mockito.when(toolService.invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString()))
                .thenReturn("催办通知已发送");
        executor.execute(readWritePlan(), specs, 107L, false, System.nanoTime());
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        Mockito.verify(toolService).invokeTool(Mockito.eq("urgeFlowInstance"), args.capture());
        Assertions.assertTrue(args.getValue().contains("\"confirm\":false"));
    }

    @Test
    @DisplayName("工具返回预检文案（写工具未带 confirm）：判定为待确认而非失败")
    void previewTreatedAsNeedConfirm() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        Mockito.when(toolService.invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.PREVIEW + "工具「urgeFlowInstance」为写操作，尚未确认执行。");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 103L, true, System.nanoTime());
        Assertions.assertTrue(result.needConfirm());
        Assertions.assertEquals(2, result.blockedStep());
    }

    @Test
    @DisplayName("失败即停：任一步返回失败信号则整链中断，后续步骤不再执行")
    void failFast() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.FORBIDDEN + "queryMyFlowInstances");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 104L, true, System.nanoTime());
        Assertions.assertEquals("failed", result.status());
        Assertions.assertEquals(0, result.executedSteps());
        Assertions.assertTrue(result.summary().contains("执行中断于第 1 步"));
        Mockito.verify(toolService, Mockito.never()).invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString());
    }

    @Test
    @DisplayName("未注册工具在执行期也不放行（运行期工具可能被停用）")
    void unknownToolBlockedAtRunTime() {
        ToolPlan plan = new ToolPlan("幻工具", List.of(
                new PlanStep(1, "notExistTool", Map.of(), "")
        ), "");
        Assertions.assertThrows(com.pivotos.common.core.exception.ServiceException.class,
                () -> executor.execute(plan, specs, 105L, true, System.nanoTime()));
    }

    @Test
    @DisplayName("逐步执行都跑在编排步骤作用域内（审计才能带出 planId/stepNo）")
    void eachStepRunsInStepScope() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenAnswer(invocation -> {
                    OrchestratorStepContext.StepRef ref = OrchestratorStepContext.get();
                    Assertions.assertNotNull(ref, "工具调用必须处在编排步骤作用域内");
                    Assertions.assertEquals(106L, ref.planId());
                    Assertions.assertEquals(1, ref.stepNo());
                    return INSTANCE_JSON;
                });
        executor.execute(readPlan(), specs, 106L, false, System.nanoTime());
        // 作用域只包裹工具调用本身，结束后自动解绑
        Assertions.assertNull(OrchestratorStepContext.get());
    }

    /* ================= A5-2 重试 / 熔断 / 轨迹（S117） ================= */

    private PlanExecutor executorWith(OrchestratorProperties props) {
        props.setMaxSteps(10);
        // 单测不等退避，避免用例被 sleep 拖慢
        props.getRetry().setBackoffMs(0);
        return new PlanExecutor(toolService, new ToolPlanValidator(props), props);
    }

    @Test
    @DisplayName("A5-2 重试：只读步骤执行失败后重试一次成功，轨迹记 attempts=2")
    void readonlyStepRetriesThenSucceeds() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.EXEC_FAILED + "连接超时", INSTANCE_JSON);
        PlanRunResult result = executor.execute(readPlan(), specs, 201L, false, System.nanoTime());
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(1, result.retryCount());
        Assertions.assertEquals(2, result.traces().get(0).attemptCount());
        Assertions.assertFalse(result.circuitBroken());
        Mockito.verify(toolService, Mockito.times(2))
                .invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString());
    }

    @Test
    @DisplayName("A5-2 写步骤零重试：写工具失败后不再重试（非幂等，重试会重复副作用）")
    void writeStepNeverRetries() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        Mockito.when(toolService.invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.EXEC_FAILED + "催办失败");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 202L, true, System.nanoTime());
        Assertions.assertEquals("failed", result.status());
        Assertions.assertEquals(0, result.retryCount(), "写步骤不得产生任何重试");
        Assertions.assertEquals(1, result.traces().get(1).attemptCount());
        Mockito.verify(toolService, Mockito.times(1))
                .invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString());
    }

    @Test
    @DisplayName("A5-2 终态失败不重试：权限拒绝一次都不重试")
    void terminalFailureNeverRetries() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.FORBIDDEN + "queryMyFlowInstances");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 203L, true, System.nanoTime());
        Assertions.assertEquals(0, result.retryCount());
        Assertions.assertFalse(result.circuitBroken(), "压根没重试就不算熔断");
        Mockito.verify(toolService, Mockito.times(1))
                .invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString());
    }

    @Test
    @DisplayName("A5-2 熔断：重试预算耗尽后不再重试，剩余步骤记 skipped")
    void circuitBreaksAfterBudgetExhausted() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.getRetry().setMaxAttempts(5);
        props.getCircuit().setMaxRetriesPerPlan(2);
        PlanExecutor exec = executorWith(props);
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(ToolGuardSignal.EXEC_FAILED + "持续失败");
        PlanRunResult result = exec.execute(readWritePlan(), specs, 204L, true, System.nanoTime());
        Assertions.assertEquals("failed", result.status());
        Assertions.assertEquals(2, result.retryCount(), "预算 2 → 最多重试 2 次");
        Assertions.assertTrue(result.circuitBroken());
        Assertions.assertTrue(result.summary().contains("熔断"));
        // 首次 + 2 次重试 = 3 次调用，第 4 次不再发生
        Mockito.verify(toolService, Mockito.times(3))
                .invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString());
        // 第 2 步从未发起调用 → 轨迹里必须能看到 skipped，而不是「轨迹少了一行」
        Assertions.assertEquals(2, result.traces().size());
        Assertions.assertEquals(PlanStepTrace.SKIPPED, result.traces().get(1).status());
        Mockito.verify(toolService, Mockito.never())
                .invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString());
    }

    @Test
    @DisplayName("A5-2 可观测：成功链每步都有轨迹，含耗时与尝试次数")
    void successTracesEveryStep() {
        Mockito.when(toolService.invokeTool(Mockito.eq("queryMyFlowInstances"), Mockito.anyString()))
                .thenReturn(INSTANCE_JSON);
        Mockito.when(toolService.invokeTool(Mockito.eq("urgeFlowInstance"), Mockito.anyString()))
                .thenReturn("催办通知已发送");
        PlanRunResult result = executor.execute(readWritePlan(), specs, 205L, true, System.nanoTime());
        Assertions.assertEquals(2, result.traces().size());
        for (PlanStepTrace trace : result.traces()) {
            Assertions.assertEquals(PlanStepTrace.SUCCESS, trace.status());
            Assertions.assertEquals(1, trace.attemptCount());
            Assertions.assertNotNull(trace.tool());
            Assertions.assertNotNull(trace.argsJson());
        }
        Assertions.assertTrue(result.traces().get(1).write(), "第 2 步是写操作");
    }
}

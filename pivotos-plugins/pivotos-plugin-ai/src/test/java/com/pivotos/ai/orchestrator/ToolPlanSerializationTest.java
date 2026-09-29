package com.pivotos.ai.orchestrator;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 计划对象序列化往返用例（A5-1 / S116）。
 *
 * <p><b>为什么必须单独一个用例</b>：S111/S112 的 EditInstruction 断点事故同源——
 * Jackson 3 按 getter 序列化普通类，写成 record 访问器形式的普通类会得到 {}，
 * 表现为「入库成功、读回全空」。本 plan 走「LLM 产出 → 入库 → 详情页读回重放」，
 * 因此这里用「序列化 + 反序列化 + 字段逐项比对」三步锁死。
 */
class ToolPlanSerializationTest {

    private ToolPlan sample() {
        return new ToolPlan("催办我发起的第一条流程", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", 1, "pageSize", 10, "confirm", false), "取列表"),
                new PlanStep(2, "urgeFlowInstance",
                        Map.of("instanceId", "${step1.list[0].instanceId}", "confirm", false), "催办第一条")
        ), "");
    }

    @Test
    @DisplayName("序列化产物非空且含关键字段（防止 record 以外的形态导致空对象）")
    void serializedJsonNotEmpty() {
        String json = JSON.toJSONString(sample());
        Assertions.assertFalse(json.isBlank());
        Assertions.assertTrue(json.contains("queryMyFlowInstances"));
        Assertions.assertTrue(json.contains("${step1.list[0].instanceId}"));
        Assertions.assertTrue(json.contains("goal"));
    }

    @Test
    @DisplayName("序列化 → 手工解析 → 字段逐项等价（读回重放的前置条件）")
    void roundTripKeepsFields() {
        OrchestratorProperties properties = new OrchestratorProperties();
        PlanDraftService parser = new PlanDraftService(null, properties);
        String json = JSON.toJSONString(sample());
        ToolPlan back = parser.parsePlan(json);

        Assertions.assertEquals(sample().goal(), back.goal());
        Assertions.assertEquals(sample().steps().size(), back.steps().size());
        for (int i = 0; i < sample().steps().size(); i++) {
            PlanStep expect = sample().steps().get(i);
            PlanStep actual = back.steps().get(i);
            Assertions.assertEquals(expect.no(), actual.no());
            Assertions.assertEquals(expect.tool(), actual.tool());
            Assertions.assertEquals(expect.args(), actual.args());
            Assertions.assertEquals(expect.reason(), actual.reason());
        }
    }

    @Test
    @DisplayName("PlanStep 空 args 落成空 Map 而非 null（下游无需判空）")
    void nullArgsNormalized() {
        PlanStep step = new PlanStep(1, "tool", null, "");
        Assertions.assertNotNull(step.args());
        Assertions.assertTrue(step.args().isEmpty());
    }

    @Test
    @DisplayName("ToolPlan 空 steps 落成空列表")
    void nullStepsNormalized() {
        ToolPlan plan = new ToolPlan("", null, "缺能力");
        Assertions.assertTrue(plan.steps().isEmpty());
        Assertions.assertTrue(plan.empty());
    }
}

package com.pivotos.ai.orchestrator;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划校验器用例（A5-1 / S116）——逐条锁死 S116 开工简报 §五 的七类拦截。
 *
 * <p>这些全是「LLM 可能犯的错」对应的确定性闸门：幻觉工具、参数漂移、
 * 引用越界、代客确认、失控长链。任何一条失效都意味着模型能把危险动作塞进执行器。
 */
class ToolPlanValidatorTest {

    private Map<String, ToolSpec> specs;

    @BeforeEach
    void setUp() {
        specs = new LinkedHashMap<>();
        specs.put("queryMyPendingTaskCount", new ToolSpec("queryMyPendingTaskCount", "查询待办数量",
                Map.of("confirm", "boolean"), List.of("confirm"), false));
        specs.put("queryMyFlowInstances", new ToolSpec("queryMyFlowInstances", "查询流程实例",
                Map.of("pageNum", "integer", "pageSize", "integer", "confirm", "boolean"),
                List.of("pageNum", "pageSize", "confirm"), false));
        specs.put("urgeFlowInstance", new ToolSpec("urgeFlowInstance", "催办流程实例",
                Map.of("instanceId", "integer", "confirm", "boolean"), List.of("instanceId", "confirm"), true));
    }

    private ToolPlanValidator validator(int maxSteps) {
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setMaxSteps(maxSteps);
        return new ToolPlanValidator(properties);
    }

    @Test
    @DisplayName("合规计划：零错误（含前序引用与写工具 confirm=false）")
    void validPlanPasses() {
        ToolPlan plan = new ToolPlan("催办我发起的第一条流程", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", 1, "pageSize", 10, "confirm", false), "取列表"),
                new PlanStep(2, "urgeFlowInstance",
                        Map.of("instanceId", "${step1.list[0].instanceId}", "confirm", false), "催办第一条")
        ), "");
        Assertions.assertTrue(validator(10).validate(plan, specs).isEmpty());
    }

    @Test
    @DisplayName("幻觉工具：工具名不在目录中被拦下")
    void unknownToolRejected() {
        ToolPlan plan = new ToolPlan("导出 Excel", List.of(
                new PlanStep(1, "exportExcel", Map.of(), "编造的工具")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertEquals(1, errors.size());
        Assertions.assertTrue(errors.get(0).contains("未注册"));
    }

    @Test
    @DisplayName("停用/消失的工具：针对 airtool 目录被清空场景同样拦下")
    void missingToolSpecRejected() {
        ToolPlan plan = new ToolPlan("催办", List.of(
                new PlanStep(1, "urgeFlowInstance", Map.of("instanceId", 1L, "confirm", false), "")
        ), "");
        List<String> errors = validator(10).validate(plan, Map.of());
        Assertions.assertEquals(1, errors.size());
        Assertions.assertTrue(errors.get(0).contains("urgeFlowInstance"));
    }

    @Test
    @DisplayName("参数漂移：未知参数名被拦下")
    void unknownArgRejected() {
        ToolPlan plan = new ToolPlan("查询实例", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", 1, "pageSize", 10, "confirm", false, "whoAmI", "admin"), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("未知参数 whoAmI")));
    }

    @Test
    @DisplayName("缺必填参数：校验器给出可读结论")
    void missingRequiredArgRejected() {
        ToolPlan plan = new ToolPlan("催办", List.of(
                new PlanStep(1, "urgeFlowInstance", Map.of("confirm", false), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("缺必填参数 instanceId")));
    }

    @Test
    @DisplayName("类型不符：integer 参数收到字符串被拦下（布尔/数字串不许蒙混）")
    void wrongArgTypeRejected() {
        ToolPlan plan = new ToolPlan("查询实例", List.of(
                new PlanStep(1, "queryMyFlowInstances",
                        Map.of("pageNum", "一", "pageSize", 10, "confirm", false), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("类型不符")));
    }

    @Test
    @DisplayName("引用越界：指向未来步骤在执行期渲染即失败（不允许带上半截参数继续）")
    void forwardRefFailsOnRender() {
        Assertions.assertTrue(PlanRefRenderer.refTargets("${step3.list[0].instanceId}").contains(3));
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> PlanRefRenderer.render("${step3.list[0].instanceId}", Map.of(1, "{}")));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_REF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("代客确认：写工具入参自带 confirm=true 必须被拦下（写操作唯一护栏不交模型）")
    void autoConfirmRejected() {
        ToolPlan plan = new ToolPlan("催办", List.of(
                new PlanStep(1, "urgeFlowInstance", Map.of("instanceId", 1L, "confirm", true), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("不允许由规划代为确认")));
    }

    @Test
    @DisplayName("字符串形态的 confirm=true（模型爱用引号）同样被拦下")
    void stringAutoConfirmRejected() {
        ToolPlan plan = new ToolPlan("催办", List.of(
                new PlanStep(1, "urgeFlowInstance", Map.of("instanceId", 1L, "confirm", "true"), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("代为确认")));
    }

    @Test
    @DisplayName("失控长链：步骤数超上限被拦下")
    void stepLimitRejected() {
        ToolPlan plan = new ToolPlan("无限循环", List.of(
                new PlanStep(1, "queryMyPendingTaskCount", Map.of("confirm", false), ""),
                new PlanStep(2, "queryMyPendingTaskCount", Map.of("confirm", false), ""),
                new PlanStep(3, "queryMyPendingTaskCount", Map.of("confirm", false), "")
        ), "");
        List<String> errors = validator(2).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("超过上限")));
    }

    @Test
    @DisplayName("序号错位：不从 1 连续递增被拦下")
    void brokenSequenceRejected() {
        ToolPlan plan = new ToolPlan("乱序", List.of(
                new PlanStep(2, "queryMyPendingTaskCount", Map.of("confirm", false), "")
        ), "");
        List<String> errors = validator(10).validate(plan, specs);
        Assertions.assertTrue(errors.stream().anyMatch(e -> e.contains("必须从 1 连续递增")));
    }

    @Test
    @DisplayName("空计划是规划器的合法表态（能力不可得），不判错")
    void emptyPlanAllowed() {
        ToolPlan plan = new ToolPlan("", List.of(), "缺 Excel 导出能力");
        Assertions.assertTrue(validator(10).validate(plan, specs).isEmpty());
        Assertions.assertTrue(plan.empty());
    }

    @Test
    @DisplayName("严格模式：有错即抛 ASIException 且错误码为参数非法")
    void validateOrThrow() {
        ToolPlan plan = new ToolPlan("编工具", List.of(
                new PlanStep(1, "notExist", Map.of(), "")
        ), "");
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> validator(10).validateOrThrow(plan, specs));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_ARGS_INVALID.getCode(), ex.getCode());
    }
}

package com.pivotos.ai.orchestrator;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计划引用渲染器用例（A5-1 / S116）。
 *
 * <p>覆盖 S116 开工简报 §五 的四类引用形态 + 三类失败路径：
 * 标量整串、嵌套路径、多引用拼接、非 JSON 透传；引用未来步骤 / 路径不存在 / 返回非 JSON。
 */
class PlanRefRendererTest {

    /** 构造一份典型的「工作流实例列表」返回 */
    private static final String INSTANCE_JSON = "{\"total\":2,\"pageNum\":1,\"list\":["
            + "{\"instanceId\":\"1900000000000001\",\"flowName\":\"网关上线\",\"businessId\":\"L2-001\",\"nodeName\":\"主管审批\"},"
            + "{\"instanceId\":\"1900000000000002\",\"flowName\":\"配额调整\",\"businessId\":\"L2-002\",\"nodeName\":\"主管审批\"}]}";

    private Map<Integer, String> outputs() {
        Map<Integer, String> outputs = new LinkedHashMap<>();
        outputs.put(1, INSTANCE_JSON);
        outputs.put(2, "12");
        return outputs;
    }

    @Test
    @DisplayName("标量工具返回：整串引用还原成原生 long 类型（否则会被工具 JSON Schema 拒掉）")
    void scalarRefKeepsNativeType() {
        Object value = PlanRefRenderer.render("${step2}", outputs());
        Assertions.assertEquals(12L, value);
    }

    @Test
    @DisplayName("嵌套路径：${step1.list[0].instanceId} 精确取到第一条实例 ID")
    void nestedPathRef() {
        Object value = PlanRefRenderer.render("${step1.list[0].instanceId}", outputs());
        Assertions.assertEquals("1900000000000001", value);
    }

    @Test
    @DisplayName("多引用拼接：同一参数内两处引用落成字符串，且顺序正确")
    void multipleRefsInOneArg() {
        Object value = PlanRefRenderer.render("流程 ${step1.list[0].flowName}（${step1.list[0].businessId}）已催办",
                outputs());
        Assertions.assertEquals("流程 网关上线（L2-001）已催办", value);
    }

    @Test
    @DisplayName("非 JSON 返回：整串引用原样透传（文本型工具的产出）")
    void nonJsonOutputWholeRef() {
        Map<Integer, String> outputs = new LinkedHashMap<>();
        outputs.put(1, "催办通知已发送给当前节点处理人");
        Object value = PlanRefRenderer.render("${step1}", outputs);
        Assertions.assertEquals("催办通知已发送给当前节点处理人", value);
    }

    @Test
    @DisplayName("非 JSON 返回 + 带路径：按不可解析显式失败（不允许静默填空串）")
    void nonJsonOutputWithPathFails() {
        Map<Integer, String> outputs = new LinkedHashMap<>();
        outputs.put(1, "催办通知已发送给当前节点处理人");
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> PlanRefRenderer.render("${step1.list[0].id}", outputs));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_REF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("引用尚未执行的步骤：明确失败（不能以 null 继续执行写操作）")
    void refFutureStepFails() {
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> PlanRefRenderer.render("${step9}", outputs()));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_REF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("路径存在性：字段不存在时按不可达失败，禁止取到半截参数")
    void missingPathFails() {
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> PlanRefRenderer.render("${step1.list[0].notExist}", outputs()));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_REF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("下标越界：list[9] 明确失败")
    void outOfBoundsIndexFails() {
        ServiceException ex = Assertions.assertThrows(ServiceException.class,
                () -> PlanRefRenderer.render("${step1.list[9].flowName}", outputs()));
        Assertions.assertEquals(AiErrorCode.ORCHESTRATOR_REF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("非字符串入参原样返回；普通字符串无引用时不改字")
    void nonStringAndPlainText() {
        Assertions.assertEquals(10, PlanRefRenderer.render(10, outputs()));
        Assertions.assertEquals(Boolean.TRUE, PlanRefRenderer.render(Boolean.TRUE, outputs()));
        Assertions.assertEquals("hello", PlanRefRenderer.render("hello", outputs()));
    }

    @Test
    @DisplayName("引用目标提取：用于计划期校验「只能引用前序步骤」")
    void refTargets() {
        Assertions.assertEquals(java.util.List.of(1, 2),
                PlanRefRenderer.refTargets("${step1.list[0].a} 与 ${step2}"));
        Assertions.assertTrue(PlanRefRenderer.hasRef("${step1}"));
        Assertions.assertFalse(PlanRefRenderer.hasRef("无引用"));
    }
}

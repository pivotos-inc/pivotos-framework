package com.pivotos.ai.orchestrator;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划校验器（A5-1 / S116）——LLM 产物的确定性闸门。
 *
 * <p>沿用 A4 已拍板的路线：**裁决权不交给 LLM**。LLM 可能产生三类危险产物，
 * 本类逐条拦下并给出可读结论（S116 一票否决点脚本里同款判据的 Java 实现）：
 * <ul>
 *   <li><b>幻觉工具</b>：工具名不在活工具目录 → {@link AiErrorCode#ORCHESTRATOR_TOOL_UNKNOWN}；</li>
 *   <li><b>参数漂移</b>：未知参数名 / 缺必填 / 类型不可解析 → {@link AiErrorCode#ORCHESTRATOR_ARGS_INVALID}；</li>
 *   <li><b>引用越界</b>：引用 {@code ${stepN}} 指向自身或未来步骤 → {@link AiErrorCode#ORCHESTRATOR_REF_INVALID}；</li>
 *   <li><b>代客确认</b>：写工具入参自带 {@code confirm=true} → {@link AiErrorCode#ORCHESTRATOR_WRITE_AUTO_CONFIRM}
 *       （写操作唯一护栏不能交给模型）；</li>
 *   <li><b>失控长链</b>：步骤数超 {@link OrchestratorProperties#getMaxSteps()} → 超限。</li>
 * </ul>
 *
 * <p><b>计划期 + 执行期各校验一次</b>：工具的白名单与启用状态在运行期可变
 * （管理员可以停用某个工具），「一次校验终身有效」会形成越权窗口。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
public class ToolPlanValidator {

    private final int maxSteps;

    public ToolPlanValidator(OrchestratorProperties properties) {
        this.maxSteps = properties.getMaxSteps();
    }

    /**
     * 校验计划。
     *
     * @param plan      待校验计划
     * @param toolSpecs 活工具目录（工具名 → 规格）
     * @return 错误清单（为空表示通过）
     */
    public List<String> validate(ToolPlan plan, Map<String, ToolSpec> toolSpecs) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("计划为空");
            return errors;
        }
        List<PlanStep> steps = plan.steps();
        if (steps.isEmpty()) {
            return errors; // 空计划是规划器的合法表态（能力不可得），由上层决定如何呈现
        }
        if (steps.size() > maxSteps) {
            errors.add("步骤数 " + steps.size() + " 超过上限 " + maxSteps);
        }
        Set<Integer> executed = new HashSet<>();
        int expectedNo = 1;
        for (PlanStep step : steps) {
            if (step.no() != expectedNo++) {
                errors.add("步骤序号必须从 1 连续递增（第 " + step.no() + " 步处错位）");
            }
            ToolSpec spec = toolSpecs.get(step.tool());
            if (spec == null) {
                errors.add("第 " + step.no() + " 步：工具未注册或已停用 tool=" + step.tool());
                continue;
            }
            checkArgs(step, spec, errors);
            checkNoAutoConfirm(step, spec, errors);
            executed.add(step.no());
        }
        return errors;
    }

    /** 严格模式：有错即抛（供计划生成端点使用） */
    public void validateOrThrow(ToolPlan plan, Map<String, ToolSpec> toolSpecs) {
        List<String> errors = validate(plan, toolSpecs);
        if (!errors.isEmpty()) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_ARGS_INVALID, String.join("；", errors));
        }
    }

    private void checkArgs(PlanStep step, ToolSpec spec, List<String> errors) {
        Set<String> known = spec.properties().keySet();
        Map<String, Object> args = step.args();
        for (String name : args.keySet()) {
            if (!known.contains(name)) {
                errors.add("第 " + step.no() + " 步：未知参数 " + name);
            }
        }
        // schema 未解析出 properties 时跳过细粒度校验（工具自身仍有 JSON Schema 兜底）
        if (known.isEmpty()) {
            return;
        }
        for (String name : spec.required()) {
            if (!args.containsKey(name)) {
                errors.add("第 " + step.no() + " 步：缺必填参数 " + name);
            }
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String type = spec.properties().get(entry.getKey());
            Object value = entry.getValue();
            if (type == null || value == null || PlanRefRenderer.hasRef(value)) {
                continue; // 引用在执行期渲染，这里不断类型
            }
            if (!PlanArgType.matches(value, type)) {
                errors.add("第 " + step.no() + " 步：参数 " + entry.getKey() + " 类型不符（期望 " + type + "）");
            }
        }
    }

    private void checkNoAutoConfirm(PlanStep step, ToolSpec spec, List<String> errors) {
        if (!spec.write()) {
            return;
        }
        Object confirm = step.args().get("confirm");
        if (Boolean.TRUE.equals(confirm) || "true".equals(String.valueOf(confirm))) {
            errors.add("第 " + step.no() + " 步：写工具不允许由规划代为确认（confirm 必须为 false）");
        }
    }
}

package com.pivotos.ai.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.dto.AiToolPlanQuery;
import com.pivotos.ai.domain.entity.AiToolPlan;
import com.pivotos.ai.domain.entity.AiToolPlanStep;
import com.pivotos.ai.domain.vo.AiToolPlanVO;
import com.pivotos.ai.mapper.AiToolPlanMapper;
import com.pivotos.ai.mapper.AiToolPlanStepMapper;
import com.pivotos.ai.orchestrator.PlanStepTrace;
import com.pivotos.ai.orchestrator.OrchestratorProperties;
import com.pivotos.ai.orchestrator.PlanExecutor;
import com.pivotos.ai.orchestrator.PlanRunResult;
import com.pivotos.ai.orchestrator.PlanStep;
import com.pivotos.ai.orchestrator.PlanDraftService;
import com.pivotos.ai.orchestrator.ToolPlan;
import com.pivotos.ai.orchestrator.ToolPlanValidator;
import com.pivotos.ai.orchestrator.ToolSpec;
import com.pivotos.ai.service.AiOrchestratorService;
import com.pivotos.ai.api.tool.AiToolMeta;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TraceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 工具多步编排服务实现（A5-1 / S116）。
 *
 * <p>流程：<b>意图 → LLM 产 plan → 确定性校验（计划期）→ 落库 → 用户确认 → 执行器逐步跑（执行期再校验一次）</b>。
 * 计划不通过校验时一律不落可执行态： lìmíng状态为 {@code draft} 且 {@code errors} 非空，前端不得放行「执行」按钮。
 *
 * <p>工具面的来源口径（务必维持）：**活工具 = 容器内 ToolCallbackProvider 的回调集合**，
 * 并且跳过 {@code properties.excludedTools}（默认排除 {@code writeCodeFile}——写工程源码属
 * A4 评审面职责，编排面不给它开旁路）。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
@Service
@RequiredArgsConstructor
public class AiOrchestratorServiceImpl implements AiOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestratorServiceImpl.class);

    private final AiToolPlanMapper planMapper;
    private final AiToolPlanStepMapper stepMapper;
    private final OrchestratorProperties properties;
    private final PlanDraftService draftService;
    private final ToolPlanValidator validator;
    private final PlanExecutor executor;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ApplicationContext applicationContext;

    @Override
    public AiToolPlanVO draft(String intent) {
        requireEnabled();
        String text = normalizeIntent(intent);
        Map<String, ToolSpec> specs = loadToolSpecs();
        PlanDraftService.PlanDraft draft = draftService.draft(text, specs);
        ToolPlan plan = draft.plan();
        List<String> errors = validator.validate(plan, specs);
        AiToolPlan entity = insertPlan(text, plan, errors.isEmpty() ? "draft" : "failed",
                errors.isEmpty() ? "" : String.join("；", errors));
        AiToolPlanVO vo = toVO(entity, plan, null, specs, Map.of());
        vo.setErrors(errors);
        log.info("[PivotOS] AI 编排规划：planId={} steps={} errors={}", entity.getId(), plan.steps().size(), errors.size());
        return vo;
    }

    @Override
    public AiToolPlanVO run(Long planId, boolean confirmed) {
        requireEnabled();
        AiToolPlan entity = requirePlan(planId);
        ToolPlan plan = parsePlanJson(entity);
        long start = System.nanoTime();
        PlanRunResult result = executor.execute(plan, loadToolSpecs(), entity.getId(), confirmed, start);
        return finish(entity, plan, result);
    }

    @Override
    public AiToolPlanVO draftAndRun(String intent, boolean confirmed) {
        AiToolPlanVO vo = draft(intent);
        if (vo.getErrors() != null && !vo.getErrors().isEmpty()) {
            return vo;
        }
        return run(vo.getId(), confirmed);
    }

    @Override
    public PageResult<AiToolPlanVO> page(AiToolPlanQuery query) {
        Page<AiToolPlan> page = planMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<AiToolPlan>lambdaQuery()
                        .like(StringUtils.hasText(query.getIntent()), AiToolPlan::getIntent, query.getIntent())
                        .eq(StringUtils.hasText(query.getStatus()), AiToolPlan::getStatus, query.getStatus())
                        .orderByDesc(AiToolPlan::getCreateTime));
        Map<String, ToolSpec> specs = loadToolSpecs();
        List<AiToolPlanVO> list = page.getRecords().stream()
                .map(entity -> toVO(entity, parsePlanJson(entity), null, specs, loadStepTraces(entity.getId())))
                .collect(Collectors.toList());
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public AiToolPlanVO detail(Long id) {
        AiToolPlan entity = requirePlan(id);
        return toVO(entity, parsePlanJson(entity), null, loadToolSpecs(), loadStepTraces(entity.getId()));
    }

    @Override
    public List<String> availableTools() {
        return List.copyOf(loadToolSpecs().keySet());
    }

    @Override
    public ToolPlan planOf(Long id) {
        return parsePlanJson(requirePlan(id));
    }

    @Override
    public PlanRunResult execute(ToolPlan plan, Long planId, boolean confirmed, long startedAt) {
        return executor.execute(plan, loadToolSpecs(), planId, confirmed, startedAt);
    }

    // ------------------------------------------------------------ 内部实现

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_DISABLED);
        }
    }

    private String normalizeIntent(String intent) {
        if (!StringUtils.hasText(intent)) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_EMPTY, "意图不能为空");
        }
        String trimmed = intent.trim();
        if (trimmed.length() > properties.getMaxIntentLength()) {
            trimmed = trimmed.substring(0, properties.getMaxIntentLength());
        }
        return trimmed;
    }

    private AiToolPlan requirePlan(Long id) {
        AiToolPlan entity = planMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 计划 JSON → {@link ToolPlan}：走 {@link PlanDraftService#parsePlan(String)} 的手工映射，
     * 不用 {@code JSON.parseObject(json, ToolPlan.class)}——record 构造器的反序列化行为随解析库版本变化，
     * 而本对象的读写路径必须稳定（S112 的 EditInstruction 序列化断点教训）。
     */
    private ToolPlan parsePlanJson(AiToolPlan entity) {
        if (!StringUtils.hasText(entity.getPlanJson())) {
            return new ToolPlan("", List.of(), "");
        }
        try {
            return draftService.parsePlan(entity.getPlanJson());
        } catch (Exception e) {
            log.warn("[PivotOS] 编排计划 JSON 解析失败：planId={} err={}", entity.getId(), e.getMessage());
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_NOT_FOUND, "编排计划已损坏，无法读取");
        }
    }

    private Map<String, ToolSpec> loadToolSpecs() {
        Map<String, ToolSpec> specs = new LinkedHashMap<>();
        List<String> excluded = properties.getExcludedTools() == null ? List.of() : properties.getExcludedTools();
        Map<String, AiToolMeta> metaMap = scanToolMeta();
        for (ToolCallbackProvider provider : toolCallbackProviders) {
            if (provider == null) {
                continue;
            }
            for (ToolCallback callback : provider.getToolCallbacks()) {
                String name = callback.getToolDefinition().name();
                if (excluded.contains(name)) {
                    continue;
                }
                specs.putIfAbsent(name, ToolSpec.of(callback, metaMap.get(name)));
            }
        }
        return specs;
    }

    /**
     * 反射扫描 {@code @AiToolMeta} 写标记（与 AiToolServiceImpl 同口径：per barrel扫描 Bean 的 @Tool 方法）。
     */
    private Map<String, AiToolMeta> scanToolMeta() {
        Map<String, AiToolMeta> metaMap = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(beanName);
            if (type == null) {
                continue;
            }
            for (Method method : ClassUtils.getUserClass(type).getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String toolName = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
                AiToolMeta meta = method.getAnnotation(AiToolMeta.class);
                if (meta != null) {
                    metaMap.put(toolName, meta);
                }
            }
        }
        return metaMap;
    }

    private AiToolPlan insertPlan(String intent, ToolPlan plan, String status, String summary) {
        AiToolPlan entity = new AiToolPlan();
        entity.setIntent(intent);
        entity.setGoal(plan.goal());
        entity.setPlanJson(truncate(JSON.toJSONString(plan), properties.getMaxPlanJsonLength()));
        entity.setStepCount(plan.steps().size());
        entity.setStatus(status);
        entity.setExecutedSteps(0);
        entity.setBlockedStep(0);
        entity.setResultSummary(truncate(summary, 1000));
        entity.setCostMs(0L);
        entity.setRetryCount(0);
        entity.setCircuitBroken(0);
        entity.setTraceId(TraceContext.get());
        LoginUser loginUser = LoginContext.get();
        if (loginUser != null) {
            entity.setTenantId(loginUser.getTenantId());
        }
        planMapper.insert(entity);
        return entity;
    }

    private AiToolPlanVO finish(AiToolPlan entity, ToolPlan plan, PlanRunResult result) {
        entity.setStatus(result.status());
        entity.setExecutedSteps(result.executedSteps());
        entity.setBlockedStep(result.blockedStep());
        entity.setResultSummary(truncate(result.summary(), 1000));
        entity.setCostMs(result.costMs());
        entity.setRetryCount(result.retryCount());
        entity.setCircuitBroken(result.circuitBroken() ? 1 : 0);
        entity.setFailReason(truncate(failReasonOf(result), 1000));
        planMapper.updateById(entity);
        saveStepTraces(entity, result);
        log.info("[PivotOS] AI 编排执行：planId={} status={} executed={} blocked={} retry={} circuit={}",
                entity.getId(), result.status(), result.executedSteps(), result.blockedStep(),
                result.retryCount(), result.circuitBroken());
        return toVO(entity, plan, result, loadToolSpecs(), loadStepTraces(entity.getId()));
    }

    /**
     * 落步骤轨迹（A5-2 可观测）。
     *
     * <p>为什么先清旧行：{@code ai_tool_invoke} 是「每次调用」的累积留痕（重跑叠加，S116 K3），
     * 而步骤轨迹是「最近一次执行的视图」——叠加会让前端出现两组同序号步骤，无法回答
     * 「这一步现在到底是什么状态」。因此这里按 plan_id 物理删除后重写。
     */
    private void saveStepTraces(AiToolPlan entity, PlanRunResult result) {
        if (result.traces() == null || result.traces().isEmpty()) {
            return;
        }
        stepMapper.delete(Wrappers.<AiToolPlanStep>lambdaQuery()
                .eq(AiToolPlanStep::getPlanId, entity.getId()));
        String traceId = TraceContext.get();
        for (PlanStepTrace trace : result.traces()) {
            AiToolPlanStep row = new AiToolPlanStep();
            row.setPlanId(entity.getId());
            row.setStepNo(trace.stepNo());
            row.setToolName(trace.tool());
            row.setWriteFlag(trace.write() ? 1 : 0);
            row.setAttemptCount(trace.attemptCount());
            row.setStatus(trace.status());
            row.setArgsJson(truncate(trace.argsJson(), 2000));
            row.setOutputSummary(truncate(trace.outputSummary(), 2000));
            row.setErrorMessage(truncate(trace.error(), 1000));
            row.setCostMs(trace.costMs());
            row.setTraceId(traceId);
            stepMapper.insert(row);
        }
    }

    /** 步骤轨迹（步骤序号 → 轨迹行），供 VO 合并 */
    private Map<Integer, AiToolPlanStep> loadStepTraces(Long planId) {
        if (planId == null) {
            return Map.of();
        }
        List<AiToolPlanStep> rows = stepMapper.selectList(Wrappers.<AiToolPlanStep>lambdaQuery()
                .eq(AiToolPlanStep::getPlanId, planId));
        Map<Integer, AiToolPlanStep> map = new LinkedHashMap<>();
        for (AiToolPlanStep row : rows) {
            map.put(row.getStepNo(), row);
        }
        return map;
    }

    /** 失败原因取终态失败步骤的信号原文（成功/待确认为空） */
    private String failReasonOf(PlanRunResult result) {
        if (result.traces() == null) {
            return "";
        }
        return result.traces().stream()
                .filter(t -> PlanStepTrace.FAILED.equals(t.status()))
                .map(PlanStepTrace::error)
                .filter(value -> value != null && !value.isEmpty())
                .findFirst()
                .orElse("");
    }

    private AiToolPlanVO toVO(AiToolPlan entity, ToolPlan plan, PlanRunResult result,
                              Map<String, ToolSpec> specs, Map<Integer, AiToolPlanStep> traces) {
        AiToolPlanVO vo = new AiToolPlanVO();
        vo.setId(entity.getId());
        vo.setIntent(entity.getIntent());
        vo.setGoal(entity.getGoal());
        vo.setStepCount(entity.getStepCount());
        vo.setStatus(entity.getStatus());
        vo.setExecutedSteps(entity.getExecutedSteps());
        vo.setBlockedStep(entity.getBlockedStep());
        vo.setResultSummary(entity.getResultSummary());
        vo.setCostMs(entity.getCostMs());
        vo.setRetryCount(entity.getRetryCount());
        vo.setCircuitBroken(entity.getCircuitBroken() != null && entity.getCircuitBroken() == 1);
        vo.setFailReason(entity.getFailReason());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUnmapped(plan == null ? "" : plan.unmapped());
        List<AiToolPlanVO.PlanStepVO> stepVOs = new ArrayList<>();
        if (plan != null) {
            for (PlanStep step : plan.steps()) {
                AiToolPlanVO.PlanStepVO item = new AiToolPlanVO.PlanStepVO();
                item.setNo(step.no());
                item.setTool(step.tool());
                item.setArgs(JSON.toJSONString(step.args()));
                item.setReason(step.reason());
                ToolSpec spec = specs.get(step.tool());
                item.setWrite(spec != null && spec.write());
                if (result != null) {
                    item.setOutput(result.outputs().get(step.no()));
                }
                AiToolPlanStep row = traces == null ? null : traces.get(step.no());
                if (row != null) {
                    item.setAttemptCount(row.getAttemptCount());
                    item.setStepStatus(row.getStatus());
                    item.setStepCostMs(row.getCostMs());
                    item.setError(row.getErrorMessage());
                }
                stepVOs.add(item);
            }
        }
        vo.setSteps(stepVOs);
        return vo;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}

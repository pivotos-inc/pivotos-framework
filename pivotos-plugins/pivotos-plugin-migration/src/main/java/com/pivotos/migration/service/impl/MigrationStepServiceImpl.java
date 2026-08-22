package com.pivotos.migration.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.enums.MigrationErrorCode;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import com.pivotos.migration.domain.entity.MigrationLog;
import com.pivotos.migration.domain.entity.MigrationReview;
import com.pivotos.migration.domain.entity.MigrationStep;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationArtifactType;
import com.pivotos.migration.domain.enums.MigrationLogLevel;
import com.pivotos.migration.domain.enums.MigrationPhase;
import com.pivotos.migration.domain.enums.MigrationStepStatus;
import com.pivotos.migration.domain.enums.MigrationTaskStatus;
import com.pivotos.migration.mapper.MigrationArtifactMapper;
import com.pivotos.migration.mapper.MigrationIrNodeMapper;
import com.pivotos.migration.mapper.MigrationLogMapper;
import com.pivotos.migration.mapper.MigrationReviewMapper;
import com.pivotos.migration.mapper.MigrationStepMapper;
import com.pivotos.migration.mapper.MigrationTaskMapper;
import com.pivotos.migration.service.MigrationProgressNotifier;
import com.pivotos.migration.service.MigrationStepService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 迁移步骤 Service 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationStepServiceImpl extends ServiceImpl<MigrationStepMapper, MigrationStep>
        implements MigrationStepService {

    private final MigrationTaskMapper migrationTaskMapper;
    private final MigrationArtifactMapper migrationArtifactMapper;
    private final MigrationReviewMapper migrationReviewMapper;
    private final MigrationIrNodeMapper migrationIrNodeMapper;
    private final MigrationLogMapper migrationLogMapper;
    /** AI Facade 可选：AI 插件未装载时静默降级 */
    private final ObjectProvider<IAiFacade> aiFacadeProvider;
    /** SSE 进度推送（方案 §9.3）：无订阅者时 publish 空转，无副作用 */
    private final MigrationProgressNotifier progressNotifier;

    /** 允许执行的步骤状态：待执行 / 已驳回（重执行） / 失败（重试） */
    private static final Set<Integer> ALLOWED_EXECUTE_STATUSES = Set.of(
            MigrationStepStatus.PENDING.getCode(),
            MigrationStepStatus.REJECTED.getCode(),
            MigrationStepStatus.FAILED.getCode());

    /** 允许执行步骤的任务状态 */
    private static final Set<Integer> ALLOWED_TASK_STATUSES = Set.of(
            MigrationTaskStatus.PLANNED.getCode(),
            MigrationTaskStatus.EXECUTING.getCode());

    /** 自测校验允许的产物扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "java", "vue", "sql", "ts", "xml", "yml", "yaml");

    private static final int MAX_CONTEXT_NODES = 10;

    // ------------------------------------------------------------------ execute

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String executeStep(Long stepId) {
        MigrationStep step = getById(stepId);
        if (step == null) {
            throw new ServiceException(MigrationErrorCode.STEP_NOT_FOUND);
        }
        if (!ALLOWED_EXECUTE_STATUSES.contains(step.getStatus())) {
            throw new ServiceException(MigrationErrorCode.STEP_STATUS_INVALID);
        }
        MigrationTask task = migrationTaskMapper.selectById(step.getTaskId());
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_TASK_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        // 步骤 → EXECUTING，任务 → EXECUTING
        step.setStatus(MigrationStepStatus.EXECUTING.getCode());
        step.setStartTime(LocalDateTime.now());
        step.setErrorMsg(null);
        updateById(step);
        if (task.getStatus() == MigrationTaskStatus.PLANNED.getCode()) {
            task.setStatus(MigrationTaskStatus.EXECUTING.getCode());
            migrationTaskMapper.updateById(task);
        }

        IAiFacade aiFacade = aiFacadeProvider.getIfAvailable();
        if (aiFacade == null) {
            log.warn("步骤执行：AI 插件未装载，stepId={}", stepId);
            saveLog(step.getTaskId(), stepId, MigrationLogLevel.WARN, MigrationPhase.GENERATE, "AI 插件未装载，无法执行步骤");
            throw new ServiceException(MigrationErrorCode.GENERATE_FAILED);
        }

        String systemPrompt = """
                你是一个资深的代码迁移工程师。请根据用户提供的迁移步骤信息与旧系统 IR 上下文，生成符合 PivotOS 规范的代码产物。
                输出格式要求：仅输出纯 JSON 数组（不要包裹 Markdown 代码块），每个元素包含如下字段：
                - artifactType: JAVA 或 VUE 或 FLYWAY 或 OTHER
                - relativePath: 字符串，产物相对目标工程根目录的路径（如 src/main/java/com/pivotos/xxx/XxxService.java）
                - content: 字符串，完整代码内容
                产物数量限制在 10 个以内，代码内容必须完整可用。
                只生成代码文件（.java/.vue/.sql/.ts/.xml/.yml），不要生成 Markdown 等文档类产物。勿输出其他内容。
                """;

        String analysisReport = task.getAnalysisReport() != null ? task.getAnalysisReport() : "";
        String reportSnippet = analysisReport.length() > 800
                ? analysisReport.substring(0, 800) + "..."
                : analysisReport;

        // 该模块 IR 节点上下文（最多 10 条）
        String moduleContext = buildModuleContext(step, task);

        String userPrompt = String.format("""
                步骤名称：%s
                步骤类型：%s
                模块标识：%s
                模块名称：%s
                步骤说明：%s
                模块 IR 节点上下文：
                %s
                架构分析报告（节选）：
                %s
                请输出代码产物 JSON 数组。
                """,
                step.getName(),
                step.getStepType(),
                step.getModuleId(),
                step.getModuleName(),
                step.getIrSnapshot(),
                moduleContext,
                reportSnippet
        );

        log.info("步骤执行开始，stepId={}, taskId={}, step={}", stepId, step.getTaskId(), step.getName());
        publishStep(step, "EXECUTING", 10, "正在调用 AI 生成代码产物");

        // AI 调用 + 解析，空返回/解析失败时重试一次
        JSONArray arr = null;
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String aiResponse;
            try {
                aiResponse = aiFacade.chatWithSystem(systemPrompt, userPrompt);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("步骤执行 AI 调用失败（第{}次），stepId={}", attempt, stepId, e);
                continue;
            }
            try {
                String json = aiResponse.trim();
                if (json.startsWith("```")) {
                    json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
                }
                JSONArray parsed = JSON.parseArray(json);
                if (parsed != null && !parsed.isEmpty()) {
                    arr = parsed;
                    break;
                }
                lastError = "AI 返回空产物列表";
                log.warn("AI 返回空产物列表（第{}次），stepId={}", attempt, stepId);
            } catch (Exception e) {
                lastError = "AI 返回内容无法解析为 JSON";
                log.warn("解析 AI 产物 JSON 失败（第{}次），stepId={}", attempt, stepId, e);
            }
        }

        if (arr == null) {
            saveLog(step.getTaskId(), stepId, MigrationLogLevel.ERROR, MigrationPhase.GENERATE,
                    "AI 产物生成失败（已重试）：" + lastError);
            step.setStatus(MigrationStepStatus.FAILED.getCode());
            step.setErrorMsg(lastError);
            step.setEndTime(LocalDateTime.now());
            updateById(step);
            publishStep(step, "FAILED", -1, "AI 产物生成失败：" + lastError);
            throw new ServiceException(MigrationErrorCode.GENERATE_FAILED);
        }

        publishStep(step, "EXECUTING", 60, "AI 产物已返回，正在执行自测校验");

        // 简化自测：逐条校验产物合法性，不合法产物丢弃并告警（不阻断整批）
        List<Map<String, Object>> checks = new ArrayList<>();
        List<MigrationArtifact> artifacts = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String artifactType = item.getString("artifactType");
            String relativePath = item.getString("relativePath");
            String content = item.getString("content");

            boolean typeValid = artifactType != null
                    && List.of(MigrationArtifactType.values()).stream().anyMatch(t -> t.getCode().equals(artifactType));
            boolean pathValid = relativePath != null && !relativePath.isBlank()
                    && ALLOWED_EXTENSIONS.contains(extOf(relativePath));
            boolean contentValid = content != null && !content.isBlank();
            boolean passed = typeValid && pathValid && contentValid;

            Map<String, Object> check = new LinkedHashMap<>();
            check.put("path", relativePath);
            check.put("typeValid", typeValid);
            check.put("pathValid", pathValid);
            check.put("contentValid", contentValid);
            check.put("passed", passed);
            checks.add(check);

            if (passed) {
                MigrationArtifact artifact = new MigrationArtifact();
                artifact.setTaskId(step.getTaskId());
                artifact.setStepId(stepId);
                artifact.setArtifactType(artifactType);
                artifact.setRelativePath(relativePath);
                artifact.setContentHash(DigestUtil.sha256Hex(content));
                artifact.setGeneratedContent(content);
                artifact.setApplied(false);
                artifacts.add(artifact);
            } else {
                log.warn("产物自测未通过已丢弃，stepId={}, path={}, typeValid={}, pathValid={}, contentValid={}",
                        stepId, relativePath, typeValid, pathValid, contentValid);
                saveLog(step.getTaskId(), stepId, MigrationLogLevel.WARN, MigrationPhase.SELF_TEST,
                        "产物不合法已丢弃：" + relativePath);
            }
        }

        if (artifacts.isEmpty()) {
            log.warn("步骤自测未通过（无合法产物），stepId={}", stepId);
            saveLog(step.getTaskId(), stepId, MigrationLogLevel.ERROR, MigrationPhase.SELF_TEST,
                    "产物自测未通过，详情：" + JSON.toJSONString(checks));
            step.setSelfTestResult(JSON.toJSONString(Map.of("passed", false, "checks", checks)));
            step.setStatus(MigrationStepStatus.FAILED.getCode());
            step.setErrorMsg("产物自测未通过");
            step.setEndTime(LocalDateTime.now());
            updateById(step);
            publishStep(step, "FAILED", -1, "产物自测未通过，无合法产物");
            throw new ServiceException(MigrationErrorCode.SELF_TEST_FAILED);
        }

        // 清除旧产物并批量插入新产物
        migrationArtifactMapper.delete(new LambdaQueryWrapper<MigrationArtifact>()
                .eq(MigrationArtifact::getStepId, stepId));
        for (MigrationArtifact artifact : artifacts) {
            migrationArtifactMapper.insert(artifact);
        }

        // 步骤 → SELF_TEST_PASSED
        List<String> paths = artifacts.stream().map(MigrationArtifact::getRelativePath).toList();
        step.setGeneratedArtifacts(JSON.toJSONString(paths));
        step.setSelfTestResult(JSON.toJSONString(Map.of("passed", true, "checks", checks)));
        step.setStatus(MigrationStepStatus.SELF_TEST_PASSED.getCode());
        step.setEndTime(LocalDateTime.now());
        updateById(step);

        saveLog(step.getTaskId(), stepId, MigrationLogLevel.INFO, MigrationPhase.SELF_TEST,
                "步骤执行完成，产物数=" + artifacts.size() + "，等待人工评审");
        publishStep(step, "SELF_TEST_PASSED", 100,
                "步骤执行完成，产物数=" + artifacts.size() + "，等待人工评审");
        log.info("步骤执行完成，stepId={}, 产物数={}", stepId, artifacts.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stepId", stepId);
        summary.put("artifacts", artifacts.size());
        summary.put("paths", paths);
        return JSON.toJSONString(summary);
    }

    // ------------------------------------------------------------------ review

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewStep(Long stepId, String action, String comment) {
        MigrationStep step = getById(stepId);
        if (step == null) {
            throw new ServiceException(MigrationErrorCode.STEP_NOT_FOUND);
        }
        if (step.getStatus() != MigrationStepStatus.SELF_TEST_PASSED.getCode()) {
            throw new ServiceException(MigrationErrorCode.STEP_STATUS_INVALID);
        }
        if (!"PASS".equals(action) && !"REJECT".equals(action)) {
            throw new ServiceException(MigrationErrorCode.REVIEW_ACTION_INVALID);
        }

        // 记录评审
        MigrationReview review = new MigrationReview();
        review.setTaskId(step.getTaskId());
        review.setStepId(stepId);
        review.setAction(action);
        review.setComment(comment);
        Object loginId = StpSysUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            review.setReviewerId(Long.parseLong(loginId.toString()));
        }
        migrationReviewMapper.insert(review);

        MigrationTask task = migrationTaskMapper.selectById(step.getTaskId());

        if ("PASS".equals(action)) {
            step.setReviewStatus(MigrationStepStatus.APPROVED.getCode());
            step.setReviewComment(comment);
            step.setStatus(MigrationStepStatus.COMPLETED.getCode());
            updateById(step);

            if (task != null) {
                int completed = (task.getCompletedSteps() != null ? task.getCompletedSteps() : 0) + 1;
                task.setCompletedSteps(completed);
                if (task.getTotalSteps() != null && completed >= task.getTotalSteps()) {
                    task.setStatus(MigrationTaskStatus.EXECUTED.getCode());
                    saveLog(step.getTaskId(), stepId, MigrationLogLevel.INFO, MigrationPhase.REVIEW,
                            "全部步骤已完成，任务进入已执行状态");
                }
                migrationTaskMapper.updateById(task);
            }
            saveLog(step.getTaskId(), stepId, MigrationLogLevel.INFO, MigrationPhase.REVIEW, "评审通过");
            publishStep(step, "APPROVED", 100, "评审通过，步骤已完成");
        } else {
            step.setStatus(MigrationStepStatus.REJECTED.getCode());
            step.setReviewStatus(MigrationStepStatus.REJECTED.getCode());
            step.setReviewComment(comment);
            updateById(step);
            saveLog(step.getTaskId(), stepId, MigrationLogLevel.WARN, MigrationPhase.REVIEW,
                    "评审驳回：" + (comment != null ? comment : ""));
            publishStep(step, "REJECTED", -1, "评审驳回，可重新执行该步骤");
        }
        log.info("步骤评审完成，stepId={}, action={}", stepId, action);
    }

    // ------------------------------------------------------------------ private

    /** 推送步骤级进度事件（STEP_PROGRESS，方案 §9.3 事件体） */
    private void publishStep(MigrationStep step, String status, int percent, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", step.getId());
        payload.put("stepNo", step.getStepNo());
        payload.put("stepName", step.getName());
        payload.put("status", status);
        payload.put("progressPercent", percent);
        payload.put("message", message);
        progressNotifier.publish(step.getTaskId(), "STEP_PROGRESS", payload);
    }

    private String buildModuleContext(MigrationStep step, MigrationTask task) {
        LambdaQueryWrapper<MigrationIrNode> wrapper = new LambdaQueryWrapper<MigrationIrNode>()
                .eq(MigrationIrNode::getTaskId, task.getId());
        if (step.getModuleId() != null && !step.getModuleId().isBlank()) {
            wrapper.eq(MigrationIrNode::getModuleId, step.getModuleId());
        }
        // 分页插件取前 N 条（禁用 last() 拼接，遵守后端规范 MP-5）
        Page<MigrationIrNode> contextPage = migrationIrNodeMapper.selectPage(
                new Page<>(1, MAX_CONTEXT_NODES), wrapper);
        List<MigrationIrNode> nodes = contextPage.getRecords();

        StringBuilder sb = new StringBuilder();
        for (MigrationIrNode node : nodes) {
            sb.append("- [").append(node.getNodeType()).append("] ")
                    .append(node.getName() != null ? node.getName() : node.getNodeId());
            if (node.getSourcePath() != null) {
                sb.append(" (").append(node.getSourcePath()).append(")");
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? "（无匹配 IR 节点）" : sb.toString();
    }

    private String extOf(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? "" : path.substring(idx + 1).toLowerCase();
    }

    private void saveLog(Long taskId, Long stepId, MigrationLogLevel level, MigrationPhase phase, String message) {
        MigrationLog migrationLog = new MigrationLog();
        migrationLog.setTaskId(taskId);
        migrationLog.setStepId(stepId);
        migrationLog.setLogLevel(level.getCode());
        migrationLog.setPhase(phase.getCode());
        migrationLog.setMessage(message);
        migrationLogMapper.insert(migrationLog);
    }
}

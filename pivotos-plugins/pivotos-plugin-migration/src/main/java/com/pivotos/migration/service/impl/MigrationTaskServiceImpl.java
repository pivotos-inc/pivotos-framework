package com.pivotos.migration.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.migration.config.MigrationProperties;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationFile;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import com.pivotos.migration.domain.entity.MigrationLog;
import com.pivotos.migration.domain.entity.MigrationStep;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.api.enums.MigrationErrorCode;
import com.pivotos.migration.domain.enums.MigrationFileType;
import com.pivotos.migration.domain.enums.MigrationLogLevel;
import com.pivotos.migration.domain.enums.MigrationPhase;
import com.pivotos.migration.domain.enums.MigrationStepStatus;
import com.pivotos.migration.domain.enums.MigrationTaskStatus;
import com.pivotos.migration.engine.parse.ParseResult;
import com.pivotos.migration.engine.parse.SourceCodeParserChain;
import com.pivotos.migration.mapper.MigrationArtifactMapper;
import com.pivotos.migration.mapper.MigrationFileMapper;
import com.pivotos.migration.mapper.MigrationIrNodeMapper;
import com.pivotos.migration.mapper.MigrationLogMapper;
import com.pivotos.migration.mapper.MigrationStepMapper;
import com.pivotos.migration.mapper.MigrationTaskMapper;
import com.pivotos.migration.service.MigrationFileService;
import com.pivotos.migration.service.MigrationProgressNotifier;
import com.pivotos.migration.service.MigrationTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 迁移任务 Service 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationTaskServiceImpl extends ServiceImpl<MigrationTaskMapper, MigrationTask>
        implements MigrationTaskService {

    private final MigrationProperties migrationProperties;
    private final MigrationFileService migrationFileService;
    private final MigrationFileMapper migrationFileMapper;
    private final MigrationArtifactMapper migrationArtifactMapper;
    private final MigrationIrNodeMapper migrationIrNodeMapper;
    private final MigrationLogMapper migrationLogMapper;
    private final MigrationStepMapper migrationStepMapper;
    private final SourceCodeParserChain parserChain;
    /** AI Facade 可选：AI 插件未装载时静默降级 */
    private final ObjectProvider<IAiFacade> aiFacadeProvider;
    /** SSE 进度推送（方案 §9.3）：无订阅者时 publish 空转，无副作用 */
    private final MigrationProgressNotifier progressNotifier;

    private static final String ZIP_EXTENSION = "zip";
    private static final Set<Integer> ALLOWED_UPLOAD_STATUSES = Set.of(
            MigrationTaskStatus.CREATED.getCode(),
            MigrationTaskStatus.UPLOADED.getCode());
    private static final Set<Integer> ALLOWED_PARSE_STATUSES = Set.of(
            MigrationTaskStatus.UPLOADED.getCode());
    private static final Set<Integer> ALLOWED_ANALYZE_STATUSES = Set.of(
            MigrationTaskStatus.ANALYZED.getCode());
    private static final Set<Integer> ALLOWED_PLAN_STATUSES = Set.of(
            MigrationTaskStatus.ANALYZED.getCode());

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadSourceArchive(Long taskId, MigrationFileType fileType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(MigrationErrorCode.UPLOAD_FILE_EMPTY);
        }
        String extension = FileUtil.extName(file.getOriginalFilename());
        if (!ZIP_EXTENSION.equalsIgnoreCase(extension)) {
            throw new ServiceException(MigrationErrorCode.UPLOAD_INVALID_ARCHIVE);
        }

        MigrationTask task = getById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_UPLOAD_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        Path sourceRoot = Paths.get(migrationProperties.getWorkspace(), String.valueOf(taskId), "source");
        Path typeDir = sourceRoot.resolve(fileType.getCode().toLowerCase());

        // 清理同类型历史上传
        FileUtil.del(typeDir.toFile());
        migrationFileService.remove(new LambdaQueryWrapper<MigrationFile>()
                .eq(MigrationFile::getTaskId, taskId)
                .eq(MigrationFile::getFileType, fileType.getCode()));

        // 更新任务状态为上传中
        task.setStatus(MigrationTaskStatus.UPLOADING.getCode());
        updateById(task);

        List<MigrationFile> indexedFiles = extractAndIndex(file, taskId, fileType, typeDir, sourceRoot);
        for (MigrationFile indexedFile : indexedFiles) {
            migrationFileMapper.insert(indexedFile);
        }

        task.setStatus(MigrationTaskStatus.UPLOADED.getCode());
        updateById(task);

        return (long) indexedFiles.size();
    }

    private List<MigrationFile> extractAndIndex(MultipartFile file, Long taskId, MigrationFileType fileType,
                                                 Path typeDir, Path sourceRoot) {
        List<MigrationFile> indexedFiles = new ArrayList<>();
        try (InputStream in = file.getInputStream();
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 条目名分隔符归一：Windows 压缩工具（如 Compress-Archive）可能产出 \ 分隔条目，
                // 不归一则被 resolve 视为单个文件名，创建目录时报 InvalidPath
                String rawName = entry.getName();
                String entryName = rawName.replace('\\', '/');
                // 目录条目判定：isDirectory 仅认 / 结尾，\ 结尾的目录条目（Compress-Archive 产物）会漏判，
                // 漏判后零字节目录被当文件落盘，后续 createDirectories 撞 FileAlreadyExists
                if (entry.isDirectory() || rawName.endsWith("/") || rawName.endsWith("\\")) {
                    continue;
                }
                Path targetFile = typeDir.resolve(entryName).normalize();
                if (!targetFile.startsWith(typeDir.normalize())) {
                    // 防御 Zip Slip
                    continue;
                }
                // 部分压缩器文件条目先于目录条目产出（copy 隐式建文件），createDirectories 需容忍同名文件已存在
                Path parent = targetFile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);

                String relativePath = sourceRoot.relativize(targetFile).toString().replace('\\', '/');
                indexedFiles.add(buildMigrationFile(taskId, fileType, targetFile, relativePath));
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("解压源码包失败", e);
            throw new ServiceException(MigrationErrorCode.UPLOAD_INVALID_ARCHIVE);
        }
        return indexedFiles;
    }

    private MigrationFile buildMigrationFile(Long taskId, MigrationFileType fileType, Path filePath, String relativePath) {
        MigrationFile migrationFile = new MigrationFile();
        migrationFile.setTaskId(taskId);
        migrationFile.setFileType(fileType.getCode());
        migrationFile.setRelativePath(relativePath);
        migrationFile.setFileName(filePath.getFileName().toString());
        migrationFile.setExtension(FileUtil.extName(filePath.toString()));
        migrationFile.setFileSize(FileUtil.size(filePath.toFile()));
        migrationFile.setContentHash(DigestUtil.sha256Hex(filePath.toFile()));
        migrationFile.setParsed(false);
        return migrationFile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long parseSourceCode(Long taskId) {
        MigrationTask task = getById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_PARSE_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        task.setStatus(MigrationTaskStatus.ANALYZING.getCode());
        updateById(task);

        List<MigrationFile> files = migrationFileMapper.selectList(
                new LambdaQueryWrapper<MigrationFile>()
                        .eq(MigrationFile::getTaskId, taskId)
                        .eq(MigrationFile::getParsed, false));

        if (files.isEmpty()) {
            task.setStatus(MigrationTaskStatus.ANALYZED.getCode());
            updateById(task);
            saveLog(taskId, MigrationLogLevel.INFO, "没有待解析的源文件");
            return 0L;
        }

        Path sourceRoot = Paths.get(migrationProperties.getWorkspace(), String.valueOf(taskId), "source");
        long irNodeCount = 0;
        int artifactCount = 0;
        int parsedFileCount = 0;

        publishPhase(taskId, "PARSE", "EXECUTING", 0, "开始解析源码，共 " + files.size() + " 个文件");
        // 进度事件节流：最多推 20 个百分比节点，避免大项目刷屏
        int progressStride = Math.max(1, files.size() / 20);
        int fileIndex = 0;

        for (MigrationFile file : files) {
            fileIndex++;
            Path filePath = sourceRoot.resolve(file.getRelativePath());
            if (!Files.exists(filePath)) {
                log.warn("源文件不存在，跳过解析：{}", filePath);
                saveLog(taskId, MigrationLogLevel.WARN, "源文件不存在，跳过：" + file.getRelativePath());
                continue;
            }

            try {
                ParseResult result = parserChain.parse(task, file, filePath);
                for (MigrationIrNode node : result.getIrNodes()) {
                    migrationIrNodeMapper.insert(node);
                    irNodeCount++;
                }
                for (MigrationArtifact artifact : result.getArtifacts()) {
                    migrationArtifactMapper.insert(artifact);
                    artifactCount++;
                }
                for (String message : result.getLogs()) {
                    saveLog(taskId, MigrationLogLevel.INFO, message);
                }

                file.setParsed(true);
                migrationFileMapper.updateById(file);
                parsedFileCount++;
                if (fileIndex % progressStride == 0) {
                    publishPhase(taskId, "PARSE", "EXECUTING", fileIndex * 100 / files.size(),
                            "正在解析：" + file.getRelativePath());
                }
            } catch (Exception e) {
                log.error("解析文件失败：{}", file.getRelativePath(), e);
                saveLog(taskId, MigrationLogLevel.ERROR, "解析文件失败：" + file.getRelativePath() + "，" + e.getMessage());
                publishPhase(taskId, "PARSE", "FAILED", -1, "解析失败：" + file.getRelativePath());
                throw new ServiceException(MigrationErrorCode.PARSE_FAILED);
            }
        }

        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("parsedFiles", parsedFileCount);
        summary.put("irNodes", irNodeCount);
        summary.put("artifacts", artifactCount);
        task.setSourceSummary(JSON.toJSONString(summary));
        task.setStatus(MigrationTaskStatus.ANALYZED.getCode());
        updateById(task);

        saveLog(taskId, MigrationLogLevel.INFO,
                "源码解析完成，文件数=" + parsedFileCount + "，IR 节点数=" + irNodeCount + "，产物数=" + artifactCount);
        publishPhase(taskId, "PARSE", "DONE", 100,
                "源码解析完成，IR 节点数=" + irNodeCount + "，产物数=" + artifactCount);
        return irNodeCount;
    }

    private void saveLog(Long taskId, MigrationLogLevel level, String message) {
        saveLog(taskId, level, MigrationPhase.PARSE, message);
    }

    /** 推送阶段级进度事件（PARSE/ANALYZE/PLAN），percent 为 -1 时表示失败事件 */
    private void publishPhase(Long taskId, String eventType, String status, int percent, String message) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("progressPercent", percent);
        payload.put("message", message);
        progressNotifier.publish(taskId, eventType, payload);
    }

    private void saveLog(Long taskId, MigrationLogLevel level, MigrationPhase phase, String message) {
        MigrationLog migrationLog = new MigrationLog();
        migrationLog.setTaskId(taskId);
        migrationLog.setLogLevel(level.getCode());
        migrationLog.setPhase(phase.getCode());
        migrationLog.setMessage(message);
        migrationLogMapper.insert(migrationLog);
    }

    // ------------------------------------------------------------------ analyze

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String analyzeSourceCode(Long taskId) {
        MigrationTask task = getById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_ANALYZE_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        IAiFacade aiFacade = aiFacadeProvider.getIfAvailable();
        if (aiFacade == null) {
            log.warn("迁移架构分析：AI 插件未装载，跳过 AI 分析，任务 taskId={}", taskId);
            saveLog(taskId, MigrationLogLevel.WARN, MigrationPhase.ANALYZE, "AI 插件未装载，已跳过架构分析");
            return "{}";
        }

        // 查询全量 IR 节点
        List<MigrationIrNode> irNodes = migrationIrNodeMapper.selectList(
                new LambdaQueryWrapper<MigrationIrNode>()
                        .eq(MigrationIrNode::getTaskId, taskId));

        if (irNodes.isEmpty()) {
            saveLog(taskId, MigrationLogLevel.WARN, MigrationPhase.ANALYZE, "未找到 IR 节点，无法进行架构分析");
            return "{}";
        }

        // 构建统计摘要：按节点类型分组计数
        Map<String, Long> typeStat = new java.util.LinkedHashMap<>();
        for (MigrationIrNode node : irNodes) {
            typeStat.merge(node.getNodeType(), 1L, Long::sum);
        }

        String backendFramework = task.getBackendFramework() != null ? task.getBackendFramework() : "未知";
        String frontendFramework = task.getFrontendFramework() != null ? task.getFrontendFramework() : "未知";

        // 构建上下文摘要，限制长度防止 prompt 过长
        StringBuilder nodeListSb = new StringBuilder();
        int limit = Math.min(irNodes.size(), 60);
        for (int i = 0; i < limit; i++) {
            MigrationIrNode n = irNodes.get(i);
            nodeListSb.append("- [").append(n.getNodeType()).append("] ")
                    .append(n.getName());
            nodeListSb.append("\n");
        }
        if (irNodes.size() > limit) {
            nodeListSb.append("... 共 ").append(irNodes.size()).append(" 个节点（仅展示前 " + limit + " 个）\n");
        }

        String systemPrompt = """
                你是一个资深架构师，擅长企业级应用架构分析与异构迁移评估。
                请根据用户提供的源码结构信息，输出简洁且实用的中文架构分析报告。
                报告包括：架构概述、模块识别、潜在风险点、迁移建议。尽量简洁，500 字以内。
                """;

        String userPrompt = String.format("""
                任务名称：%s
                待迁移源码来自系统：后端框架=%s，前端框架=%s
                IR 节点类型分布：%s
                IR 节点列表（部分）：
                %s
                请出具架构分析报告。
                """,
                task.getName(),
                backendFramework,
                frontendFramework,
                JSON.toJSONString(typeStat),
                nodeListSb.toString()
        );

        log.info("迁移架构分析开始，taskId={}, irNodes={}", taskId, irNodes.size());
        publishPhase(taskId, "ANALYZE", "EXECUTING", 10, "正在调用 AI 生成架构分析报告");
        String report;
        try {
            report = aiFacade.chatWithSystem(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("迁移架构分析 AI 调用失败，taskId={}", taskId, e);
            saveLog(taskId, MigrationLogLevel.ERROR, MigrationPhase.ANALYZE,
                    "AI 架构分析失败：" + e.getMessage());
            publishPhase(taskId, "ANALYZE", "FAILED", -1, "AI 架构分析失败：" + e.getMessage());
            throw new ServiceException(MigrationErrorCode.ANALYZE_FAILED);
        }

        // 将报告写回任务，状态保持 ANALYZED（分析完成，等待计划生成）
        task.setAnalysisReport(report);
        updateById(task);

        saveLog(taskId, MigrationLogLevel.INFO, MigrationPhase.ANALYZE,
                "架构分析完成，报告长度=" + report.length());
        publishPhase(taskId, "ANALYZE", "DONE", 100, "架构分析完成，报告长度=" + report.length());
        log.info("迁移架构分析完成，taskId={}, 报告长度={}", taskId, report.length());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("irNodes", irNodes.size());
        result.put("typeStat", typeStat);
        result.put("reportLength", report.length());
        return JSON.toJSONString(result);
    }

    // ------------------------------------------------------------------ plan

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateMigrationPlan(Long taskId) {
        MigrationTask task = getById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_PLAN_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        // 状态推进 → PLANNING(5)
        task.setStatus(MigrationTaskStatus.PLANNING.getCode());
        updateById(task);

        IAiFacade aiFacade = aiFacadeProvider.getIfAvailable();
        if (aiFacade == null) {
            log.warn("迁移计划生成：AI 插件未装载，跳过计划生成，taskId={}", taskId);
            saveLog(taskId, MigrationLogLevel.WARN, MigrationPhase.PLAN, "AI 插件未装载，已跳过计划生成");
            task.setStatus(MigrationTaskStatus.PLANNED.getCode());
            updateById(task);
            return "{}";
        }

        // 查询 IR 节点统计
        List<MigrationIrNode> irNodes = migrationIrNodeMapper.selectList(
                new LambdaQueryWrapper<MigrationIrNode>()
                        .eq(MigrationIrNode::getTaskId, taskId));

        Map<String, Long> typeStat = new java.util.LinkedHashMap<>();
        for (MigrationIrNode node : irNodes) {
            typeStat.merge(node.getNodeType(), 1L, Long::sum);
        }

        String analysisReport = task.getAnalysisReport() != null ? task.getAnalysisReport() : "";
        String reportSnippet = analysisReport.length() > 1000
                ? analysisReport.substring(0, 1000) + "..."
                : analysisReport;

        String systemPrompt = """
                你是一个资深的迁移架构师。请根据用户提供的架构分析报告和 IR 节点信息，生成一个结构化的迁移步骤计划。
                输出格式要求：仅输出纯 JSON 数组（不要包裹 Markdown 代码块），每个元素包含如下字段：
                - stepNo: 整数，步骤序号，从 1 开始
                - name: 字符串，步骤名称（简洁，中文，20 字以内）
                - stepType: BACKEND 或 FRONTEND 或 DB
                - moduleId: 字符串，模块标识（英文小写下划线）
                - moduleName: 字符串，模块中文名称
                - description: 字符串，该步骤主要内容说明（100 字以内）
                步骤数量限制在 20 条以内。勿输出其他内容。
                """;

        StringBuilder typeStatSb = new StringBuilder();
        typeStat.forEach((type, count) -> typeStatSb.append(type).append(":").append(count).append(", "));

        String userPrompt = String.format("""
                任务名称：%s
                IR 节点类型分布：%s
                架构分析报告（节选）：
                %s
                请输出迁移步骤计划 JSON 数组。
                """,
                task.getName(),
                typeStatSb,
                reportSnippet
        );

        log.info("迁移计划生成开始，taskId={}, irNodes={}", taskId, irNodes.size());
        publishPhase(taskId, "PLAN", "EXECUTING", 10, "正在调用 AI 生成迁移步骤计划");
        String aiResponse;
        try {
            aiResponse = aiFacade.chatWithSystem(systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("迁移计划 AI 调用失败，taskId={}", taskId, e);
            saveLog(taskId, MigrationLogLevel.ERROR, MigrationPhase.PLAN, "AI 计划生成失败：" + e.getMessage());
            publishPhase(taskId, "PLAN", "FAILED", -1, "AI 计划生成失败：" + e.getMessage());
            throw new ServiceException(MigrationErrorCode.ANALYZE_FAILED);
        }

        // 解析 AI 返回的 JSON 数组，容错降级为空列表
        List<MigrationStep> steps = new ArrayList<>();
        try {
            // 清除可能的 Markdown 代码块包裹
            String json = aiResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
            }
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                MigrationStep step = new MigrationStep();
                step.setTaskId(taskId);
                step.setStepNo(item.getIntValue("stepNo", i + 1));
                step.setName(item.getString("name"));
                step.setStepType(item.getString("stepType"));
                step.setModuleId(item.getString("moduleId"));
                step.setModuleName(item.getString("moduleName"));
                step.setStatus(0);
                // 将 description 存入 ir_snapshot（LONGTEXT，初期占位描述，执行阶段再替换为真实快照）
                step.setIrSnapshot(item.getString("description"));
                steps.add(step);
            }
        } catch (Exception e) {
            log.warn("解析 AI 计划 JSON 失败，降级为空列表，taskId={}", taskId, e);
            saveLog(taskId, MigrationLogLevel.WARN, MigrationPhase.PLAN,
                    "AI 返回内容无法解析为 JSON，跳过步骤创建");
        }

        // 清除旧步骤并批量插入新步骤
        migrationStepMapper.delete(new LambdaQueryWrapper<MigrationStep>()
                .eq(MigrationStep::getTaskId, taskId));
        for (MigrationStep step : steps) {
            migrationStepMapper.insert(step);
        }

        // 写回任务：migration_plan 是 LONGTEXT 类型，直接存入 AI 原始文本
        task.setMigrationPlan(aiResponse);
        task.setTotalSteps(steps.size());
        task.setStatus(MigrationTaskStatus.PLANNED.getCode());
        updateById(task);

        saveLog(taskId, MigrationLogLevel.INFO, MigrationPhase.PLAN,
                "迁移计划生成完成，步骤数=" + steps.size());
        publishPhase(taskId, "PLAN", "DONE", 100, "迁移计划生成完成，步骤数=" + steps.size());
        log.info("迁移计划生成完成，taskId={}, 步骤数={}", taskId, steps.size());

        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("steps", steps.size());
        summary.put("typeStat", typeStat);
        summary.put("planLength", aiResponse.length());
        return JSON.toJSONString(summary);
    }

    // ------------------------------------------------------------------ complete

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeTask(Long taskId) {
        MigrationTask task = getById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (task.getStatus() != MigrationTaskStatus.EXECUTED.getCode()) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }
        // 全部步骤必须已完成
        long notCompleted = migrationStepMapper.selectCount(new LambdaQueryWrapper<MigrationStep>()
                .eq(MigrationStep::getTaskId, taskId)
                .ne(MigrationStep::getStatus, MigrationStepStatus.COMPLETED.getCode()));
        if (notCompleted > 0) {
            throw new ServiceException(MigrationErrorCode.STEP_STATUS_INVALID);
        }
        task.setStatus(MigrationTaskStatus.COMPLETED.getCode());
        updateById(task);
        saveLog(taskId, MigrationLogLevel.INFO, MigrationPhase.COMPLETE, "迁移任务已完成");
        log.info("迁移任务已完成，taskId={}", taskId);
    }
}

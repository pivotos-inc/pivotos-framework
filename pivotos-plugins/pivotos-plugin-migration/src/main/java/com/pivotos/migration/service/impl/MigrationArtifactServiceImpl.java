package com.pivotos.migration.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.enums.MigrationErrorCode;
import com.pivotos.migration.config.MigrationProperties;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationLog;
import com.pivotos.migration.domain.entity.MigrationStep;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationLogLevel;
import com.pivotos.migration.domain.enums.MigrationPhase;
import com.pivotos.migration.domain.enums.MigrationStepStatus;
import com.pivotos.migration.domain.enums.MigrationTaskStatus;
import com.pivotos.migration.mapper.MigrationArtifactMapper;
import com.pivotos.migration.mapper.MigrationLogMapper;
import com.pivotos.migration.mapper.MigrationStepMapper;
import com.pivotos.migration.mapper.MigrationTaskMapper;
import com.pivotos.migration.service.MigrationArtifactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * 迁移产物 Service 实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationArtifactServiceImpl extends ServiceImpl<MigrationArtifactMapper, MigrationArtifact>
        implements MigrationArtifactService {

    private final MigrationProperties migrationProperties;
    private final MigrationStepMapper migrationStepMapper;
    private final MigrationTaskMapper migrationTaskMapper;
    private final MigrationLogMapper migrationLogMapper;

    /** 允许落盘的任务状态：执行中 / 已执行 */
    private static final Set<Integer> ALLOWED_TASK_STATUSES = Set.of(
            MigrationTaskStatus.EXECUTING.getCode(),
            MigrationTaskStatus.EXECUTED.getCode());

    // ------------------------------------------------------------------ apply

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyArtifact(Long artifactId) {
        MigrationArtifact artifact = getById(artifactId);
        if (artifact == null) {
            throw new ServiceException(MigrationErrorCode.ARTIFACT_NOT_FOUND);
        }
        MigrationStep step = migrationStepMapper.selectById(artifact.getStepId());
        if (step == null) {
            throw new ServiceException(MigrationErrorCode.STEP_NOT_FOUND);
        }
        // 未评审完成的步骤产物不允许落盘
        if (step.getStatus() != MigrationStepStatus.COMPLETED.getCode()) {
            throw new ServiceException(MigrationErrorCode.STEP_STATUS_INVALID);
        }
        MigrationTask task = migrationTaskMapper.selectById(artifact.getTaskId());
        if (task == null || !ALLOWED_TASK_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        Path target = resolveTargetPath(artifact.getTaskId(), artifact.getRelativePath());
        try {
            FileUtil.mkdir(target.getParent().toFile());
            Files.writeString(target, artifact.getGeneratedContent() != null
                    ? artifact.getGeneratedContent() : "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("产物落盘失败，artifactId={}, path={}", artifactId, target, e);
            throw new ServiceException(MigrationErrorCode.GENERATE_FAILED);
        }

        artifact.setApplied(true);
        updateById(artifact);
        saveLog(artifact.getTaskId(), artifact.getStepId(), MigrationLogLevel.INFO, MigrationPhase.GENERATE,
                "产物已落盘：" + artifact.getRelativePath());
        log.info("产物落盘成功，artifactId={}, path={}", artifactId, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unapplyArtifact(Long artifactId) {
        MigrationArtifact artifact = getById(artifactId);
        if (artifact == null) {
            throw new ServiceException(MigrationErrorCode.ARTIFACT_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(artifact.getApplied())) {
            return;
        }
        Path target = resolveTargetPath(artifact.getTaskId(), artifact.getRelativePath());
        FileUtil.del(target.toFile());

        artifact.setApplied(false);
        updateById(artifact);
        saveLog(artifact.getTaskId(), artifact.getStepId(), MigrationLogLevel.INFO, MigrationPhase.GENERATE,
                "产物落盘已撤销：" + artifact.getRelativePath());
        log.info("产物落盘撤销成功，artifactId={}, path={}", artifactId, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long applyStep(Long stepId) {
        MigrationStep step = migrationStepMapper.selectById(stepId);
        if (step == null) {
            throw new ServiceException(MigrationErrorCode.STEP_NOT_FOUND);
        }
        List<MigrationArtifact> artifacts = list(new LambdaQueryWrapper<MigrationArtifact>()
                .eq(MigrationArtifact::getStepId, stepId));
        long count = 0;
        for (MigrationArtifact artifact : artifacts) {
            applyArtifact(artifact.getId());
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ rollback

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long rollbackTask(Long taskId) {
        MigrationTask task = migrationTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        if (!ALLOWED_TASK_STATUSES.contains(task.getStatus())) {
            throw new ServiceException(MigrationErrorCode.TASK_STATUS_INVALID);
        }

        // 删除目标目录下全部已落盘文件
        long deletedCount = 0;
        List<MigrationArtifact> applied = list(new LambdaQueryWrapper<MigrationArtifact>()
                .eq(MigrationArtifact::getTaskId, taskId)
                .eq(MigrationArtifact::getApplied, true));
        for (MigrationArtifact artifact : applied) {
            Path target = resolveTargetPath(taskId, artifact.getRelativePath());
            FileUtil.del(target.toFile());
            artifact.setApplied(false);
            updateById(artifact);
            deletedCount++;
        }

        task.setStatus(MigrationTaskStatus.ROLLED_BACK.getCode());
        task.setRolledBack(true);
        migrationTaskMapper.updateById(task);

        saveLog(taskId, null, MigrationLogLevel.INFO, MigrationPhase.ROLLBACK,
                "任务回滚完成，删除已落盘文件数=" + deletedCount);
        log.info("任务回滚完成，taskId={}, 删除文件数={}", taskId, deletedCount);
        return deletedCount;
    }

    // ------------------------------------------------------------------ private

    /**
     * 解析落盘目标路径，防路径穿越：规范化后必须位于任务 target 目录内。
     */
    private Path resolveTargetPath(Long taskId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ServiceException(MigrationErrorCode.ARTIFACT_PATH_INVALID);
        }
        Path targetRoot = Paths.get(migrationProperties.getWorkspace(), String.valueOf(taskId), "target")
                .toAbsolutePath().normalize();
        Path target = targetRoot.resolve(relativePath).normalize();
        if (!target.startsWith(targetRoot)) {
            throw new ServiceException(MigrationErrorCode.ARTIFACT_PATH_INVALID);
        }
        return target;
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

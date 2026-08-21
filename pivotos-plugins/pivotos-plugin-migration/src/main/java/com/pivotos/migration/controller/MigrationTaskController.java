package com.pivotos.migration.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationFileType;
import com.pivotos.migration.service.MigrationArtifactService;
import com.pivotos.migration.service.MigrationTaskService;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import cn.dev33.satoken.annotation.SaCheckPermission;

/**
 * 迁移任务管理端点。
 */
@Tag(name = "迁移任务管理")
@RestController
@RequestMapping("/migration/task")
@RequiredArgsConstructor
public class MigrationTaskController {

    private final MigrationTaskService migrationTaskService;
    private final MigrationArtifactService migrationArtifactService;

    @Operation(summary = "创建迁移任务")
    @PostMapping
    public R<Long> create(@RequestBody MigrationTask task) {
        migrationTaskService.save(task);
        return R.ok(task.getId());
    }

    @Operation(summary = "获取迁移任务详情")
    @GetMapping("/{id}")
    public R<MigrationTask> detail(@PathVariable Long id) {
        return R.ok(migrationTaskService.getById(id));
    }

    @Operation(summary = "迁移任务分页列表")
    @GetMapping("/page")
    public R<PageResult<MigrationTask>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<MigrationTask> result = migrationTaskService.page(new Page<>(pageNum, pageSize));
        return R.ok(new PageResult<>(result.getRecords(), result.getTotal(), pageNum, pageSize));
    }

    @Operation(summary = "上传源码压缩包")
    @PostMapping("/upload")
    public R<Long> upload(@RequestParam("taskId") Long taskId,
                          @RequestParam("fileType") String fileType,
                          @RequestParam("file") MultipartFile file) {
        return R.ok(migrationTaskService.uploadSourceArchive(taskId, MigrationFileType.valueOf(fileType), file));
    }

    @Operation(summary = "解析源码生成 IR 节点")
    @PostMapping("/parse")
    public R<Long> parse(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationTaskService.parseSourceCode(taskId));
    }

    @Operation(summary = "AI 架构分析，生成分析报告")
    @PostMapping("/analyze")
    public R<String> analyze(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationTaskService.analyzeSourceCode(taskId));
    }

    @Operation(summary = "AI 生成迁移步骤计划")
    @PostMapping("/plan")
    public R<String> plan(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationTaskService.generateMigrationPlan(taskId));
    }

    @Operation(summary = "完成迁移任务")
    @PostMapping("/complete")
    public R<Void> complete(@RequestParam("taskId") Long taskId) {
        migrationTaskService.completeTask(taskId);
        return R.ok();
    }

    @Operation(summary = "任务级回滚：删除全部已落盘文件")
    @PostMapping("/rollback")
    public R<Long> rollback(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationArtifactService.rollbackTask(taskId));
    }
}

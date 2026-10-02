package com.pivotos.migration.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.migration.api.enums.MigrationErrorCode;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationFileType;
import com.pivotos.migration.service.MigrationArtifactService;
import com.pivotos.migration.service.MigrationProgressNotifier;
import com.pivotos.migration.service.MigrationTaskService;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;

/**
 * 迁移任务管理端点。
 * 进度流用 POST + SseEmitter：EventSource 带不了 Authorization 头，
 * 前端用 fetch + ReadableStream 消费（与 AI 对话流式端点同款约定）。
 */
@Tag(name = "迁移任务管理")
@RestController
@RequestMapping("/migration/task")
@RequiredArgsConstructor
public class MigrationTaskController {

    private final MigrationTaskService migrationTaskService;
    private final MigrationArtifactService migrationArtifactService;
    private final MigrationProgressNotifier migrationProgressNotifier;

    @Operation(summary = "创建迁移任务")
    @PostMapping
    public R<Long> create(@RequestBody MigrationTask task) {
        migrationTaskService.save(task);
        return R.ok(task.getId());
    }

    /**
     * 获取迁移任务详情。
     *
     * <p>内容为空与记录不存在必须可区分（搭档修复：原实现恒 {@code R.ok(null)}，
     * 调用方只能拿到 {@code code=0 成功}，无法区分「ID 不存在」与「存在但字段为空」，
     * E2E/排障表现为 downstream KeyError）。同域写端点早已抛 {@code TASK_NOT_FOUND 8000}，
     * 此处对齐同一码，不新增错误码、不加 Flyway、不改前端。
     */
    @Operation(summary = "获取迁移任务详情（记录不存在返回 8000，字段为空仍返回 code=0）")
    @GetMapping("/{id}")
    public R<MigrationTask> detail(@PathVariable Long id) {
        MigrationTask task = migrationTaskService.getById(id);
        if (task == null) {
            throw new ServiceException(MigrationErrorCode.TASK_NOT_FOUND);
        }
        return R.ok(task);
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

    @Operation(summary = "迁移进度 SSE 流（PARSE/ANALYZE/PLAN/STEP_PROGRESS/REVIEW/APPLY 事件）")
    @PostMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@RequestParam("taskId") Long taskId) {
        return migrationProgressNotifier.subscribe(taskId);
    }

    @Operation(summary = "完成迁移任务")
    @PostMapping("/complete")
    public R<Void> complete(@RequestParam("taskId") Long taskId) {
        migrationTaskService.completeTask(taskId);
        return R.ok();
    }

    // S124：本端点会删步骤/产物行，必须至少要求登录。全项目鉴权是注解驱动的
    // （AuthAutoConfiguration 只注册 SaInterceptor，不做全局路由拦截），同类迁移端点
    // 因为没有注解而匿名可达——那是 P1 权限码体系的既有欠账，但「删数据」的新口子
    // 不能裸奔。这里刻意用 @SaCheckLogin 而不是 @SaCheckPermission：
    // 权限码尚未进 sys_menu，用后者会让 super_admin 也被 403。
    // type 必须显式给 StpSysUtil.TYPE：默认 realm 是「login」，管端 token 会被判未登录（1002）。
    @SaCheckLogin(type = StpSysUtil.TYPE)
    @Operation(summary = "计划复位：清除步骤与未落盘产物，任务回到已分析（L10 幂等自愈入口，需登录）")
    @PostMapping("/reset")
    public R<Integer> reset(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationTaskService.resetPlan(taskId));
    }

    @Operation(summary = "任务级回滚：删除全部已落盘文件")
    @PostMapping("/rollback")
    public R<Long> rollback(@RequestParam("taskId") Long taskId) {
        return R.ok(migrationArtifactService.rollbackTask(taskId));
    }
}

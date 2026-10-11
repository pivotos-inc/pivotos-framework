package com.pivotos.migration.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.common.core.result.R;
import com.pivotos.migration.domain.entity.MigrationStep;
import com.pivotos.migration.service.MigrationStepService;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import cn.dev33.satoken.annotation.SaCheckLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 迁移步骤管理端点。
 *
 * <p><b>类级 {@code @SaCheckLogin}（v3.1.0 生产化 · A4）</b>：与 {@code MigrationTaskController}
 * 同源同因——实证 {@code GET /migration/step/list?taskId=1} 匿名即 {@code code=0}。
 * 原 §10-K 扫描只打了无参 GET，把带必填 query 参数的这类端点整个漏掉了。
 */
@Tag(name = "迁移步骤管理")
@RestController
@RequestMapping("/migration/step")
@RequiredArgsConstructor
@SaCheckLogin(type = StpSysUtil.TYPE)
public class MigrationStepController {

    private final MigrationStepService migrationStepService;

    @Operation(summary = "查询迁移步骤列表")
    @GetMapping("/list")
    public R<List<MigrationStep>> list(@RequestParam("taskId") Long taskId) {
        List<MigrationStep> steps = migrationStepService.list(
                new LambdaQueryWrapper<MigrationStep>()
                        .eq(MigrationStep::getTaskId, taskId)
                        .orderByAsc(MigrationStep::getStepNo));
        return R.ok(steps);
    }

    @Operation(summary = "执行迁移步骤（AI 生成产物）")
    @PostMapping("/execute")
    public R<String> execute(@RequestParam("stepId") Long stepId) {
        return R.ok(migrationStepService.executeStep(stepId));
    }

    @Operation(summary = "人工评审步骤（PASS/REJECT）")
    @PostMapping("/review")
    public R<Void> review(@RequestParam("stepId") Long stepId,
                          @RequestParam("action") String action,
                          @RequestParam(value = "comment", required = false) String comment) {
        migrationStepService.reviewStep(stepId, action, comment);
        return R.ok();
    }
}

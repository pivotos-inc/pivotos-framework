package com.pivotos.migration.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.common.core.result.R;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.service.MigrationArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 迁移产物管理端点。
 */
@Tag(name = "迁移产物管理")
@RestController
@RequestMapping("/migration/artifact")
@RequiredArgsConstructor
public class MigrationArtifactController {

    private final MigrationArtifactService migrationArtifactService;

    @Operation(summary = "查询步骤产物列表（不含代码内容）")
    @GetMapping("/list")
    public R<List<MigrationArtifact>> list(@RequestParam("stepId") Long stepId) {
        List<MigrationArtifact> artifacts = migrationArtifactService.list(
                new LambdaQueryWrapper<MigrationArtifact>()
                        .select(MigrationArtifact::getId, MigrationArtifact::getTaskId,
                                MigrationArtifact::getStepId, MigrationArtifact::getArtifactType,
                                MigrationArtifact::getRelativePath, MigrationArtifact::getContentHash,
                                MigrationArtifact::getApplied, MigrationArtifact::getCreateTime)
                        .eq(MigrationArtifact::getStepId, stepId)
                        .orderByAsc(MigrationArtifact::getId));
        return R.ok(artifacts);
    }

    @Operation(summary = "获取产物详情（含代码内容）")
    @GetMapping("/{id}")
    public R<MigrationArtifact> detail(@PathVariable Long id) {
        return R.ok(migrationArtifactService.getById(id));
    }

    @Operation(summary = "产物落盘到目标目录")
    @PostMapping("/apply")
    public R<Void> apply(@RequestParam("artifactId") Long artifactId) {
        migrationArtifactService.applyArtifact(artifactId);
        return R.ok();
    }

    @Operation(summary = "撤销产物落盘")
    @PostMapping("/unapply")
    public R<Void> unapply(@RequestParam("artifactId") Long artifactId) {
        migrationArtifactService.unapplyArtifact(artifactId);
        return R.ok();
    }

    @Operation(summary = "批量落盘指定步骤的全部产物")
    @PostMapping("/apply-step")
    public R<Long> applyStep(@RequestParam("stepId") Long stepId) {
        return R.ok(migrationArtifactService.applyStep(stepId));
    }
}

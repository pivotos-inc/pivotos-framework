package com.pivotos.docsync.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.docsync.api.dto.DocSyncConfigDTO;
import com.pivotos.docsync.api.dto.DocSyncConfigQuery;
import com.pivotos.docsync.api.dto.DocSyncConfigSaveRequest;
import com.pivotos.docsync.api.dto.PlatformInfoDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.service.DocSyncConfigService;
import com.pivotos.docsync.service.DocSyncService;
import com.pivotos.starter.auth.account.StpSysUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * 文档同步管理
 *
 * <p>提供同步配置的增删改查、连通性测试、手动同步、OpenAPI JSON 导出等接口。
 * 权限前缀：system:docsync:*
 */
@Tag(name = "文档同步", description = "API 文档同步配置与同步")
@RestController
@RequestMapping("/system/docsync")
@RequiredArgsConstructor
public class DocSyncController {

    private final DocSyncConfigService configService;
    private final DocSyncService syncService;

    // ==================== 配置管理 ====================

    /** 分页查询同步配置 */
    @Operation(summary = "分页查询同步配置")
    @GetMapping("/page")
    @SaCheckPermission(value = "system:docsync:list", type = StpSysUtil.TYPE)
    public R<PageResult<DocSyncConfigDTO>> page(DocSyncConfigQuery query) {
        return R.ok(configService.pageConfigs(query));
    }

    /** 获取配置详情 */
    @Operation(summary = "获取配置详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:docsync:query", type = StpSysUtil.TYPE)
    public R<DocSyncConfigDTO> get(@PathVariable Long id) {
        return R.ok(configService.getConfig(id));
    }

    /** 新增/修改配置 */
    @Operation(summary = "新增/修改配置")
    @PostMapping
    @SaCheckPermission(value = "system:docsync:add", type = StpSysUtil.TYPE)
    public R<Long> save(@Validated @RequestBody DocSyncConfigSaveRequest request) {
        return R.ok(configService.saveConfig(request));
    }

    /** 修改配置 */
    @Operation(summary = "修改配置")
    @PutMapping
    @SaCheckPermission(value = "system:docsync:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody DocSyncConfigSaveRequest request) {
        configService.saveConfig(request);
        return R.ok();
    }

    /** 删除配置 */
    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:docsync:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        configService.deleteConfig(id);
        return R.ok();
    }

    /** 切换启用状态 */
    @Operation(summary = "切换启用状态")
    @PutMapping("/{id}/status")
    @SaCheckPermission(value = "system:docsync:edit", type = StpSysUtil.TYPE)
    public R<Void> changeStatus(@PathVariable Long id, @RequestBody Integer enabled) {
        configService.changeStatus(id, enabled);
        return R.ok();
    }

    // ==================== 同步操作 ====================

    /** 获取所有支持的平台信息（含配置字段元数据） */
    @Operation(summary = "获取所有支持的平台信息（含配置字段元数据）")
    @GetMapping("/platforms")
    @SaCheckPermission(value = "system:docsync:query", type = StpSysUtil.TYPE)
    public R<List<PlatformInfoDTO>> platforms() {
        return R.ok(syncService.listSupportedPlatforms());
    }

    /** 获取指定平台的配置字段元数据 */
    @Operation(summary = "获取指定平台的配置字段元数据")
    @GetMapping("/platforms/{type}")
    @SaCheckPermission(value = "system:docsync:query", type = StpSysUtil.TYPE)
    public R<PlatformInfoDTO> platformInfo(@PathVariable DocSyncTypeEnum type) {
        return R.ok(syncService.getPlatformInfo(type));
    }

    /** 测试平台连通性 */
    @Operation(summary = "测试平台连通性")
    @PostMapping("/{id}/test")
    @SaCheckPermission(value = "system:docsync:test", type = StpSysUtil.TYPE)
    public R<Boolean> testConnection(@PathVariable Long id) {
        return R.ok(syncService.testConnection(id));
    }

    /** 同步单个配置 */
    @Operation(summary = "同步单个配置")
    @PostMapping("/{id}/sync")
    @SaCheckPermission(value = "system:docsync:sync", type = StpSysUtil.TYPE)
    public R<SyncResultDTO> syncOne(@PathVariable Long id) {
        return R.ok(syncService.syncOne(id));
    }

    /** 同步所有启用的配置 */
    @Operation(summary = "同步所有启用的配置")
    @PostMapping("/sync-all")
    @SaCheckPermission(value = "system:docsync:sync", type = StpSysUtil.TYPE)
    public R<List<SyncResultDTO>> syncAll() {
        return R.ok(syncService.syncAll());
    }

    /** 导出 OpenAPI JSON（供手动同步平台使用） */
    @Operation(summary = "导出 OpenAPI JSON（供手动同步平台使用）")
    @GetMapping("/{id}/export")
    @SaCheckPermission(value = "system:docsync:sync", type = StpSysUtil.TYPE)
    public void exportOpenApiJson(@PathVariable Long id, HttpServletResponse response) throws IOException {
        String json = syncService.exportOpenApiJson(id);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=openapi.json");
        PrintWriter writer = response.getWriter();
        writer.write(json);
        writer.flush();
    }
}

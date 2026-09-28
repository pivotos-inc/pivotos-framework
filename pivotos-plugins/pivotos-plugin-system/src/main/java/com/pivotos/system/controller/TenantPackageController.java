package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.TenantPackageQuery;
import com.pivotos.system.domain.dto.TenantPackageSaveRequest;
import com.pivotos.system.domain.vo.TenantPackageVO;
import com.pivotos.system.service.TenantPackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.List;

/** 租户套餐管理 */
@Tag(name = "租户套餐管理", description = "租户套餐 CRUD（功能开关集合）")
@RestController
@RequestMapping("/system/tenant-package")
@RequiredArgsConstructor
public class TenantPackageController {

    private final TenantPackageService tenantPackageService;

    /** 套餐列表 */
    @Operation(summary = "套餐列表")
    @GetMapping("/list")
    @SaCheckPermission(value = "system:tenant-package:query", type = StpSysUtil.TYPE)
    public R<List<TenantPackageVO>> list(TenantPackageQuery query) {
        return R.ok(tenantPackageService.listPackages(query));
    }

    /** 详情 */
    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:tenant-package:query", type = StpSysUtil.TYPE)
    public R<TenantPackageVO> get(@PathVariable Long id) {
        return R.ok(tenantPackageService.getPackage(id));
    }

    /** 新增 */
    @Operation(summary = "新增")
    @PostMapping
    @SaCheckPermission(value = "system:tenant-package:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody TenantPackageSaveRequest request) {
        return R.ok(tenantPackageService.createPackage(request));
    }

    /** 修改 */
    @Operation(summary = "修改")
    @PutMapping
    @SaCheckPermission(value = "system:tenant-package:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody TenantPackageSaveRequest request) {
        tenantPackageService.updatePackage(request);
        return R.ok();
    }

    /** 删除 */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:tenant-package:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        tenantPackageService.deletePackage(id);
        return R.ok();
    }
}

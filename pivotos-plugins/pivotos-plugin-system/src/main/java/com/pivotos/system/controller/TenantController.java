package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.TenantInitRequest;
import com.pivotos.system.domain.dto.TenantQuery;
import com.pivotos.system.domain.dto.TenantSaveRequest;
import com.pivotos.system.domain.vo.TenantInitVO;
import com.pivotos.system.domain.vo.TenantVO;
import com.pivotos.system.service.TenantService;
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

/** 租户管理 */
@Tag(name = "租户管理", description = "租户 CRUD + 初始化向导")
@RestController
@RequestMapping("/system/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    /** 租户分页 */
    @Operation(summary = "租户分页")
    @GetMapping("/page")
    @SaCheckPermission(value = "system:tenant:query", type = StpSysUtil.TYPE)
    public R<PageResult<TenantVO>> page(TenantQuery query) {
        return R.ok(tenantService.pageTenants(query));
    }

    /** 详情 */
    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:tenant:query", type = StpSysUtil.TYPE)
    public R<TenantVO> get(@PathVariable Long id) {
        return R.ok(tenantService.getTenant(id));
    }

    /** 新增 */
    @Operation(summary = "新增")
    @PostMapping
    @SaCheckPermission(value = "system:tenant:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody TenantSaveRequest request) {
        return R.ok(tenantService.createTenant(request));
    }

    /** 修改（含状态切换） */
    @Operation(summary = "修改（含状态切换）")
    @PutMapping
    @SaCheckPermission(value = "system:tenant:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody TenantSaveRequest request) {
        tenantService.updateTenant(request);
        return R.ok();
    }

    /** 删除 */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:tenant:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        tenantService.deleteTenant(id);
        return R.ok();
    }

    /** 初始化向导：建租户 → 配套餐 → 建管理员（单事务） */
    @Operation(summary = "初始化向导（建租户+配套餐+建管理员）")
    @PostMapping("/init")
    @SaCheckPermission(value = "system:tenant:init", type = StpSysUtil.TYPE)
    public R<TenantInitVO> init(@Validated @RequestBody TenantInitRequest request) {
        return R.ok(tenantService.initTenant(request));
    }
}

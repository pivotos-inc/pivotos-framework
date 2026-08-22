package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.RoleQuery;
import com.pivotos.system.domain.dto.RoleSaveRequest;
import com.pivotos.system.domain.vo.RoleVO;
import com.pivotos.system.service.RoleService;
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

/** 角色管理 */
@Tag(name = "角色管理", description = "角色 CRUD、数据权限、菜单授权")
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** 分页查询 */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @SaCheckPermission(value = "system:role:list", type = StpSysUtil.TYPE)
    public R<PageResult<RoleVO>> page(RoleQuery query) {
        return R.ok(roleService.pageRoles(query));
    }

    /** 全部正常角色（下拉选项，登录即可读） */
    @Operation(summary = "全部正常角色（下拉选项，登录即可读）")
    @GetMapping("/all")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<List<RoleVO>> all() {
        return R.ok(roleService.listAllEnabled());
    }

    /** 详情 */
    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:role:query", type = StpSysUtil.TYPE)
    public R<RoleVO> get(@PathVariable Long id) {
        return R.ok(roleService.getRole(id));
    }

    /** 角色已授权菜单ID集合（编辑回显） */
    @Operation(summary = "角色已授权菜单ID集合（编辑回显）")
    @GetMapping("/{id}/menu-ids")
    @SaCheckPermission(value = "system:role:query", type = StpSysUtil.TYPE)
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(roleService.listMenuIds(id));
    }

    /** 新增 */
    @Operation(summary = "新增")
    @PostMapping
    @SaCheckPermission(value = "system:role:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody RoleSaveRequest request) {
        return R.ok(roleService.createRole(request));
    }

    /** 修改 */
    @Operation(summary = "修改")
    @PutMapping
    @SaCheckPermission(value = "system:role:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody RoleSaveRequest request) {
        roleService.updateRole(request);
        return R.ok();
    }

    /** 删除 */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:role:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return R.ok();
    }
}

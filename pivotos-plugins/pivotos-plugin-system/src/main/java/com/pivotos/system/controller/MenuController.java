package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.MenuQuery;
import com.pivotos.system.domain.dto.MenuSaveRequest;
import com.pivotos.system.domain.vo.MenuVO;
import com.pivotos.system.domain.vo.RouterVO;
import com.pivotos.system.service.MenuService;
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

/** 菜单管理 + 动态路由下发 */
@Tag(name = "菜单管理", description = "菜单管理 + 动态路由下发")
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 菜单树查询 */
    @Operation(summary = "菜单树查询")
    @GetMapping("/tree")
    @SaCheckPermission(value = "system:menu:list", type = StpSysUtil.TYPE)
    public R<List<MenuVO>> tree(MenuQuery query) {
        return R.ok(menuService.treeMenus(query));
    }

    /** 当前用户动态路由（登录即可读） */
    @Operation(summary = "当前用户动态路由（登录即可读）")
    @GetMapping("/routers")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<List<RouterVO>> routers() {
        return R.ok(menuService.listRoutersByUserId(
                com.pivotos.starter.core.context.LoginContext.getUserId()));
    }

    /** 详情 */
    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:menu:query", type = StpSysUtil.TYPE)
    public R<MenuVO> get(@PathVariable Long id) {
        return R.ok(menuService.getMenu(id));
    }

    /** 新增 */
    @Operation(summary = "新增")
    @PostMapping
    @SaCheckPermission(value = "system:menu:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody MenuSaveRequest request) {
        return R.ok(menuService.createMenu(request));
    }

    /** 修改 */
    @Operation(summary = "修改")
    @PutMapping
    @SaCheckPermission(value = "system:menu:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody MenuSaveRequest request) {
        menuService.updateMenu(request);
        return R.ok();
    }

    /** 删除 */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:menu:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return R.ok();
    }
}

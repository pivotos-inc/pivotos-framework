package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.api.annotation.Log;
import com.pivotos.system.api.enums.OperType;
import com.pivotos.system.domain.dto.ResetPasswordBody;
import com.pivotos.system.domain.dto.UserQuery;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.vo.UserVO;
import com.pivotos.system.service.UserService;
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

/** 用户管理 */
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 分页查询 */
    @GetMapping("/page")
    @SaCheckPermission(value = "system:user:list", type = StpSysUtil.TYPE)
    public R<PageResult<UserVO>> page(UserQuery query) {
        return R.ok(userService.pageUsers(query));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:user:query", type = StpSysUtil.TYPE)
    public R<UserVO> get(@PathVariable Long id) {
        return R.ok(userService.getUser(id));
    }

    /** 新增 */
    @PostMapping
    @SaCheckPermission(value = "system:user:add", type = StpSysUtil.TYPE)
    @Log(module = "用户管理", type = OperType.CREATE)
    public R<Long> create(@Validated @RequestBody UserSaveRequest request) {
        return R.ok(userService.createUser(request));
    }

    /** 修改 */
    @PutMapping
    @SaCheckPermission(value = "system:user:edit", type = StpSysUtil.TYPE)
    @Log(module = "用户管理", type = OperType.UPDATE)
    public R<Void> update(@Validated @RequestBody UserSaveRequest request) {
        userService.updateUser(request);
        return R.ok();
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:user:remove", type = StpSysUtil.TYPE)
    @Log(module = "用户管理", type = OperType.DELETE)
    public R<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return R.ok();
    }

    /** 重置密码（入参含密码，切面脱敏为 ***） */
    @PutMapping("/reset-password")
    @SaCheckPermission(value = "system:user:resetPwd", type = StpSysUtil.TYPE)
    @Log(module = "用户管理", type = OperType.UPDATE)
    public R<Void> resetPassword(@Validated @RequestBody ResetPasswordBody body) {
        userService.resetPassword(body);
        return R.ok();
    }
}

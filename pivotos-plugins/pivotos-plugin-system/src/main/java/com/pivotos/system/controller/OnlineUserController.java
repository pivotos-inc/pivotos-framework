package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.OnlineUserQuery;
import com.pivotos.system.domain.vo.OnlineUserVO;
import com.pivotos.system.service.OnlineUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线用户管理（基于 Sa-Token 会话）。
 */
@Tag(name = "在线用户", description = "基于 Sa-Token 的在线会话管理")
@RestController
@RequestMapping("/system/online-user")
@RequiredArgsConstructor
public class OnlineUserController {

    private final OnlineUserService onlineUserService;

    /** 分页查询在线用户列表，按登录时间倒序 */
    @Operation(summary = "分页查询在线用户列表，按登录时间倒序")
    @GetMapping("/list")
    @SaCheckPermission(value = "system:online-user:list", type = StpSysUtil.TYPE)
    public R<PageResult<OnlineUserVO>> listOnlineUsers(OnlineUserQuery query) {
        return R.ok(onlineUserService.pageOnlineUsers(query));
    }

    /** 强退指定 Token 对应的会话 */
    @Operation(summary = "强退指定 Token 对应的会话")
    @DeleteMapping("/kickout/{tokenValue}")
    @SaCheckPermission(value = "system:online-user:kickout", type = StpSysUtil.TYPE)
    public R<Void> kickout(@PathVariable String tokenValue) {
        onlineUserService.kickoutByToken(tokenValue);
        return R.ok();
    }

    /** 清空所有在线用户（保留当前用户） */
    @Operation(summary = "清空所有在线用户（保留当前用户）")
    @DeleteMapping("/clear-all")
    @SaCheckPermission(value = "system:online-user:kickout", type = StpSysUtil.TYPE)
    public R<String> clearAll() {
        int count = onlineUserService.clearAllUsers();
        return R.ok("已清空 " + count + " 个在线用户");
    }
}

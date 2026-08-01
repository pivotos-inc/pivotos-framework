package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.vo.OnlineUserVO;
import com.pivotos.system.service.OnlineUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线用户管理（基于 Sa-Token 会话）。
 */
@RestController
@RequestMapping("/system/online-user")
@RequiredArgsConstructor
public class OnlineUserController {

    private final OnlineUserService onlineUserService;

    /** 查询在线用户列表 */
    @GetMapping("/list")
    @SaCheckPermission(value = "system:online-user:list", type = StpSysUtil.TYPE)
    public R<List<OnlineUserVO>> listOnlineUsers() {
        return R.ok(onlineUserService.listOnlineUsers());
    }

    /** 强退指定 Token 对应的会话 */
    @DeleteMapping("/kickout/{tokenValue}")
    @SaCheckPermission(value = "system:online-user:kickout", type = StpSysUtil.TYPE)
    public R<Void> kickout(@PathVariable String tokenValue) {
        onlineUserService.kickoutByToken(tokenValue);
        return R.ok();
    }
}

package com.pivotos.system.controller;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.domain.dto.ChangePasswordBody;
import com.pivotos.system.domain.dto.ProfileUpdateRequest;
import com.pivotos.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端个人中心接口（sys / app / wx-mini 三账号体系通用，
 * 登录态由 LoginContextFilter 统一解析，操作对象恒为本人）。
 */
@Tag(name = "个人中心", description = "移动端个人中心接口")
@RestController
@RequestMapping("/app/system/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;

    /** 修改本人资料（昵称/头像/邮箱/手机号） */
    @Operation(summary = "修改本人资料（昵称/头像/邮箱/手机号）")
    @PutMapping
    public R<Void> update(@Validated @RequestBody ProfileUpdateRequest request) {
        userService.updateProfile(requireUserId(), request);
        return R.ok();
    }

    /** 修改本人密码（旧密码校验） */
    @Operation(summary = "修改本人密码（旧密码校验）")
    @PutMapping("/password")
    public R<Void> changePassword(@Validated @RequestBody ChangePasswordBody body) {
        userService.changePassword(requireUserId(), body);
        return R.ok();
    }

    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}

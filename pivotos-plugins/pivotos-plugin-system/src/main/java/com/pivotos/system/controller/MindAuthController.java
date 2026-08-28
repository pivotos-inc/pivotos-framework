package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpMindUtil;
import com.pivotos.system.convert.UserConvert;
import com.pivotos.system.domain.dto.MindLoginBody;
import com.pivotos.system.domain.dto.MindPasswordBody;
import com.pivotos.system.domain.vo.MindLoginVO;
import com.pivotos.system.domain.vo.MindUserInfoVO;
import com.pivotos.system.service.MindAuthService;
import com.pivotos.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 枢磐·智域认证接口（mind-user 独立 Token 体系）。
 * 个人端小程序一键登录，未注册自动建档，无需手机号/账密绑定。
 */
@Tag(name = "智域认证", description = "枢磐·智域个人端认证接口")
@RestController
@RequestMapping("/mind/auth")
@RequiredArgsConstructor
public class MindAuthController {

    private final MindAuthService mindAuthService;
    private final UserService userService;
    private final UserConvert userConvert;

    /** 小程序登录（code2session，自动建档/绑定） */
    @Operation(summary = "小程序登录（自动建档）")
    @PostMapping("/login")
    public R<MindLoginVO> login(@Validated @RequestBody MindLoginBody body) {
        return R.ok(mindAuthService.login(body.getCode()));
    }

    /** 账密登录（H5 开发调试兜底） */
    @Operation(summary = "账密登录（H5 开发调试）")
    @PostMapping("/password")
    public R<MindLoginVO> password(@Validated @RequestBody MindPasswordBody body) {
        return R.ok(mindAuthService.passwordLogin(body.getUsername(), body.getPassword()));
    }

    /** 退出登录 */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @SaCheckLogin(type = StpMindUtil.TYPE)
    public R<Void> logout() {
        StpMindUtil.logout();
        return R.ok();
    }

    /** 当前登录用户信息 */
    @Operation(summary = "当前登录用户信息")
    @GetMapping("/getInfo")
    @SaCheckLogin(type = StpMindUtil.TYPE)
    public R<MindUserInfoVO> getInfo() {
        Long userId = (Long) StpMindUtil.getLoginIdDefaultNull();
        return R.ok(new MindUserInfoVO(
                userConvert.toVo(userService.getById(userId)),
                Collections.emptyList(),
                Collections.emptyList()));
    }
}

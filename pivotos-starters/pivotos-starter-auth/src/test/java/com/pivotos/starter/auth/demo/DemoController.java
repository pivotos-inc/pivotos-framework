package com.pivotos.starter.auth.demo;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.lock.annotation.Lock4j;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.auth.support.AuthSessionHolder;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TraceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 步骤 3 总验收 demo：登录 → 鉴权 → 上下文 → 异常 → 分布式锁
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    /**
     * 登录：固定演示账号 admin/123456
     */
    @PostMapping("/login")
    public R<String> login(@RequestBody Map<String, String> body) {
        if (!"admin".equals(body.get("username")) || !"123456".equals(body.get("password"))) {
            throw new ServiceException(2001, "账号或密码错误");
        }
        String token = StpSysUtil.login(1L);
        AuthSessionHolder.saveLoginUser(StpSysUtil.STP,
                new LoginUser(1L, "admin", StpSysUtil.TYPE, null));
        return R.ok(token);
    }

    /**
     * 需登录：读取 LoginContext / TraceContext
     */
    @SaCheckLogin(type = StpSysUtil.TYPE)
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", LoginContext.getUserId());
        data.put("username", LoginContext.getUsername());
        data.put("traceId", TraceContext.get());
        return R.ok(data);
    }

    /**
     * 需权限 user:list（默认空权限源 → 应返回 1003）
     */
    @SaCheckPermission(value = "user:list", type = StpSysUtil.TYPE)
    @GetMapping("/users")
    public R<String> users() {
        return R.ok("has-permission");
    }

    /**
     * 业务异常 → 统一响应
     */
    @GetMapping("/error")
    public R<Void> error() {
        throw new ServiceException("演示业务异常");
    }

    /**
     * 分布式锁
     */
    @Lock4j(keys = {"#key"}, acquireTimeout = 0)
    @GetMapping("/lock")
    public R<String> lock(@RequestParam String key) {
        return R.ok("lock-acquired:" + key);
    }
}

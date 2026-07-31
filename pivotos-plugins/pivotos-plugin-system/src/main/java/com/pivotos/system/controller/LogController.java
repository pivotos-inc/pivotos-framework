package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.LoginLogQuery;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.vo.LoginLogVO;
import com.pivotos.system.domain.vo.OperLogVO;
import com.pivotos.system.service.LoginLogService;
import com.pivotos.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 日志管理（登录日志 + 操作日志，查询只读） */
@RestController
@RequestMapping("/system/log")
@RequiredArgsConstructor
public class LogController {

    private final LoginLogService loginLogService;
    private final OperLogService operLogService;

    /** 登录日志分页 */
    @GetMapping("/login/page")
    @SaCheckPermission(value = "system:loginlog:list", type = StpSysUtil.TYPE)
    public R<PageResult<LoginLogVO>> loginLogPage(LoginLogQuery query) {
        return R.ok(loginLogService.pageLogs(query));
    }

    /** 操作日志分页 */
    @GetMapping("/oper/page")
    @SaCheckPermission(value = "system:operlog:list", type = StpSysUtil.TYPE)
    public R<PageResult<OperLogVO>> operLogPage(OperLogQuery query) {
        return R.ok(operLogService.pageLogs(query));
    }
}

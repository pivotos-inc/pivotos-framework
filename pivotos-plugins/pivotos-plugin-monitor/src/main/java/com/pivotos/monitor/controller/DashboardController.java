package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.domain.vo.DashboardSummaryVO;
import com.pivotos.monitor.service.DashboardService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营看板（S71 PL-REPORT 一期）：工作台与数据大屏共用的聚合只读端点。
 * 登录即可访问（工作台为首页）；数据大屏页面可见性由菜单权限 monitor:bigscreen:view 控制。
 */
@RestController
@RequestMapping("/monitor/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 聚合统计（系统/工作流/文件/AI/知识库 + 在线用户 + 近 7 日趋势） */
    @GetMapping("/summary")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<DashboardSummaryVO> summary() {
        return R.ok(dashboardService.summary());
    }
}

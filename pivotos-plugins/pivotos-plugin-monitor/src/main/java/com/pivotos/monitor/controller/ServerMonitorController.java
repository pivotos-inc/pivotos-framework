package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.domain.vo.ServerInfoVO;
import com.pivotos.monitor.service.ServerInfoService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务监控（S48 2.3-F6）：CPU/内存/磁盘/JVM/虚拟线程快照，只读。
 */
@Tag(name = "服务监控", description = "CPU/内存/磁盘/JVM 快照")
@RestController
@RequestMapping("/monitor/server")
@RequiredArgsConstructor
public class ServerMonitorController {

    private final ServerInfoService serverInfoService;

    /** 服务器实时快照 */
    @Operation(summary = "服务器实时快照")
    @GetMapping
    @SaCheckPermission(value = "monitor:server:list", type = StpSysUtil.TYPE)
    public R<ServerInfoVO> info() {
        return R.ok(serverInfoService.collect());
    }
}

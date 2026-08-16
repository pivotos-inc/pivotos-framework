package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.domain.vo.CacheInfoVO;
import com.pivotos.monitor.service.CacheInfoService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存监控（S48 2.3-F6）：Redis INFO 关键指标 + 键空间/命令统计，只读。
 */
@Tag(name = "缓存监控", description = "Redis 缓存监控")
@RestController
@RequestMapping("/monitor/cache")
@RequiredArgsConstructor
public class CacheMonitorController {

    private final CacheInfoService cacheInfoService;

    /** Redis 实时快照 */
    @Operation(summary = "Redis 实时快照")
    @GetMapping
    @SaCheckPermission(value = "monitor:cache:list", type = StpSysUtil.TYPE)
    public R<CacheInfoVO> info() {
        return R.ok(cacheInfoService.collect());
    }
}

package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.domain.vo.EsInfoVO;
import com.pivotos.monitor.service.EsInfoService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ES 监控：集群健康 / 节点 / 索引 / JVM / 分片快照 + 当前生效的搜索实现与回落状态，只读。
 * <p>降级口径：simple / 未启用 / 连接不可达均返回 {@code available=false} + 原因文案，绝不抛异常。
 */
@Tag(name = "ES 监控", description = "Elasticsearch 集群与搜索实现健康快照")
@RestController
@RequestMapping("/monitor/es")
@RequiredArgsConstructor
public class EsMonitorController {

    private final EsInfoService esInfoService;

    /** ES 实时快照 */
    @Operation(summary = "ES 实时快照")
    @GetMapping
    @SaCheckPermission(value = "monitor:es:list", type = StpSysUtil.TYPE)
    public R<EsInfoVO> info() {
        return R.ok(esInfoService.collect());
    }
}

package com.pivotos.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.domain.vo.AiUsageProviderVO;
import com.pivotos.ai.domain.vo.AiUsageSummaryVO;
import com.pivotos.ai.domain.vo.AiUsageUserVO;
import com.pivotos.ai.service.AiUsageService;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI Token 用量监控接口（S92）
 *
 * <p>面向平台管理员的成本管控视图：按供应商/Key/用户/场景聚合近 N 日调用量与 token 消耗。
 * 权限点 ai:usage:list 由「用量监控」菜单（3050）挂载。
 */
@Tag(name = "AI 用量监控", description = "Token 调用量与消耗统计（S92）")
@RestController
@RequestMapping("/ai/usage")
@RequiredArgsConstructor
public class AiUsageController {

    private final AiUsageService aiUsageService;

    /** 汇总：总量卡片 + 场景分布 + 日趋势 */
    @Operation(summary = "用量汇总（总量/场景分布/日趋势）")
    @GetMapping("/summary")
    @SaCheckPermission(value = "ai:usage:list", type = StpSysUtil.TYPE)
    public R<AiUsageSummaryVO> summary(@RequestParam(defaultValue = "7") int days) {
        return R.ok(aiUsageService.summary(days));
    }

    /** 按供应商 × Key 聚合 */
    @Operation(summary = "按供应商 × Key 聚合")
    @GetMapping("/by-provider")
    @SaCheckPermission(value = "ai:usage:list", type = StpSysUtil.TYPE)
    public R<List<AiUsageProviderVO>> byProvider(@RequestParam(defaultValue = "7") int days) {
        return R.ok(aiUsageService.byProvider(days));
    }

    /** 按用户聚合 */
    @Operation(summary = "按用户聚合")
    @GetMapping("/by-user")
    @SaCheckPermission(value = "ai:usage:list", type = StpSysUtil.TYPE)
    public R<List<AiUsageUserVO>> byUser(@RequestParam(defaultValue = "7") int days) {
        return R.ok(aiUsageService.byUser(days));
    }
}

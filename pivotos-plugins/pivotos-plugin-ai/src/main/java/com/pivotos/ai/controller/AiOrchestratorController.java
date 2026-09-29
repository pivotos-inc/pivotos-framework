package com.pivotos.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.domain.dto.AiOrchestratorRunRequest;
import com.pivotos.ai.domain.dto.AiToolPlanQuery;
import com.pivotos.ai.domain.vo.AiToolPlanVO;
import com.pivotos.ai.service.AiOrchestratorService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 工具多步编排接口（A5-1 / S116）。
 *
 * <p>权限口径沿用 S112「新增页面尽量复用既有权限码」的取舍：
 * <ul>
 *   <li>编排执行（写 tools/call 的放大版）→ 复用 {@code ai:tool:invoke:list} 所属的工具面权限，
 *       这里新开 {@code ai:orchestrator:run} 一条，因为它能连续触发多个写工具，权限面必须比单工具调用更窄；</li>
 *   <li>记录查看 → {@code ai:orchestrator:list}。</li>
 * </ul>
 */
@Tag(name = "AI 工具编排管理", description = "多步调用链规划 / 执行 / 记录（S116 A5-1）")
@RestController
@RequestMapping("/ai/orchestrator")
@RequiredArgsConstructor
public class AiOrchestratorController {

    private final AiOrchestratorService orchestratorService;

    @Operation(summary = "按意图生成调用链计划（不执行任何工具）")
    @PostMapping("/plan")
    @SaCheckPermission(value = "ai:orchestrator:run", type = StpSysUtil.TYPE)
    public R<AiToolPlanVO> plan(@Validated @RequestBody AiOrchestratorRunRequest request) {
        return R.ok(orchestratorService.draft(request.getIntent()));
    }

    @Operation(summary = "生成计划并立即执行（含写步骤且未确认时停在写步骤前）")
    @PostMapping("/run")
    @SaCheckPermission(value = "ai:orchestrator:run", type = StpSysUtil.TYPE)
    public R<AiToolPlanVO> run(@Validated @RequestBody AiOrchestratorRunRequest request) {
        return R.ok(orchestratorService.draftAndRun(request.getIntent(), Boolean.TRUE.equals(request.getConfirmed())));
    }

    @Operation(summary = "按计划 ID 执行（二次确认后置confirmed=true 继续）")
    @PostMapping("/{id}/run")
    @SaCheckPermission(value = "ai:orchestrator:run", type = StpSysUtil.TYPE)
    public R<AiToolPlanVO> rerun(@PathVariable Long id,
                                 @RequestBody(required = false) AiOrchestratorRunRequest request) {
        boolean confirmed = request != null && Boolean.TRUE.equals(request.getConfirmed());
        return R.ok(orchestratorService.run(id, confirmed));
    }

    @Operation(summary = "编排记录分页查询")
    @GetMapping("/page")
    @SaCheckPermission(value = "ai:orchestrator:list", type = StpSysUtil.TYPE)
    public R<PageResult<AiToolPlanVO>> page(AiToolPlanQuery query) {
        return R.ok(orchestratorService.page(query));
    }

    @Operation(summary = "编排明细（含每步入参与输出）")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "ai:orchestrator:list", type = StpSysUtil.TYPE)
    public R<AiToolPlanVO> detail(@PathVariable Long id) {
        return R.ok(orchestratorService.detail(id));
    }

    @Operation(summary = "编排可用工具清单（排除配置中的高危工具）")
    @GetMapping("/tools")
    @SaCheckPermission(value = "ai:orchestrator:list", type = StpSysUtil.TYPE)
    public R<List<String>> tools() {
        return R.ok(orchestratorService.availableTools());
    }
}

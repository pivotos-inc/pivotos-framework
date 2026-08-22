package com.pivotos.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.fastjson2.JSON;
import com.pivotos.ai.domain.dto.AiToolInvokeQuery;
import com.pivotos.ai.domain.dto.AiToolQuery;
import com.pivotos.ai.domain.dto.ToolCallRequest;
import com.pivotos.ai.domain.dto.ToolRoleUpdateRequest;
import com.pivotos.ai.domain.vo.AiToolInvokeVO;
import com.pivotos.ai.domain.vo.AiToolVO;
import com.pivotos.ai.service.AiToolService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 工具注册管理接口（S98 A2，S99 补 REST 直连调用）
 *
 * <p>权限点暂由 @SaCheckPermission 注解直管（super_admin 通配放行），
 * 管理页菜单与权限点登记随 S99 首批业务工具批次一并落地。
 *
 * <p>/call 端点不设管理权限点：任意登录用户可发起，具体工具的访问控制
 * 由守卫层角色白名单叠加业务归属校验承担（同 MCP tools/call 口径）。
 */
@Tag(name = "AI 工具注册管理", description = "工具元数据 / 角色白名单 / 调用审计（S98 A2）")
@RestController
@RequestMapping("/ai/tool")
@RequiredArgsConstructor
public class AiToolController {

    private final AiToolService aiToolService;

    /** 工具分页查询（附角色白名单） */
    @Operation(summary = "工具分页查询")
    @GetMapping("/page")
    @SaCheckPermission(value = "ai:tool:list", type = StpSysUtil.TYPE)
    public R<PageResult<AiToolVO>> page(AiToolQuery query) {
        return R.ok(aiToolService.pageTools(query));
    }

    /** 全量更新工具角色白名单 */
    @Operation(summary = "全量更新工具角色白名单（空数组 = 登录用户皆可调用）")
    @PutMapping("/{id}/roles")
    @SaCheckPermission(value = "ai:tool:edit", type = StpSysUtil.TYPE)
    public R<Void> updateRoles(@PathVariable Long id, @Validated @RequestBody ToolRoleUpdateRequest request) {
        aiToolService.updateRoleWhitelist(id, request.getRoles());
        return R.ok();
    }

    /** 停用/启用工具 */
    @Operation(summary = "停用/启用工具（0正常 1停用）")
    @PutMapping("/{id}/status")
    @SaCheckPermission(value = "ai:tool:edit", type = StpSysUtil.TYPE)
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        aiToolService.updateStatus(id, status);
        return R.ok();
    }

    /** 调用审计分页查询 */
    @Operation(summary = "工具调用审计分页查询")
    @GetMapping("/invoke/page")
    @SaCheckPermission(value = "ai:tool:invoke:list", type = StpSysUtil.TYPE)
    public R<PageResult<AiToolInvokeVO>> invokePage(AiToolInvokeQuery query) {
        return R.ok(aiToolService.pageInvokes(query));
    }

    /** REST 直连调用工具（S99 双暴露：与 MCP tools/call 同守卫链路） */
    @Operation(summary = "REST 直连调用工具（与 MCP tools/call 同源守卫：注册闸/白名单/二次确认/审计）")
    @PostMapping("/call")
    public R<String> call(@Validated @RequestBody ToolCallRequest request) {
        String argsJson = request.getArgs() == null ? "{}" : JSON.toJSONString(request.getArgs());
        return R.ok(aiToolService.invokeTool(request.getToolName(), argsJson));
    }
}

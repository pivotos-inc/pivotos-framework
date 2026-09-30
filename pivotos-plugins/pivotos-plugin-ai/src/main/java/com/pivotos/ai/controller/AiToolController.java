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
import com.pivotos.ai.tool.ToolCallStreamer;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.core.context.ContextExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

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

    private static final Logger log = LoggerFactory.getLogger(AiToolController.class);

    private final AiToolService aiToolService;

    /**
     * 上下文感知执行器（虚拟线程 + ScopedValue 传播）。
     *
     * <p>SSE 入口不能同步调用工具：① 工具可能耗时（绕 LLM 的 writeCodeFile 等），请求线程会一直被占；
     * ② {@code LoginContext} 是 ScopedValue，切到别的线程就丢，而 {@link com.pivotos.ai.tool.GuardedToolCallback}
     * 的角色白名单判定要读当前登录用户——不经 {@code ContextExecutor} 重绑上下文，
     * 守卫会把正常调用判成「未登录/无权限」。这也是 A6 红线（禁裸线程）的直接后果。
     */
    @Qualifier("contextExecutor")
    private final ExecutorService contextExecutor;

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

    /**
     * SSE 流式调用工具（S118 AI-2 四入口之三：REST / MCP / **SSE** / CLI）。
     *
     * <p>帧协议 {@code meta → result|error → done}（见 {@link ToolCallStreamer}）。
     * 用 POST 而非 GET：{@code EventSource} 带不了 {@code Authorization} 头，
     * 项目所有 SSE 端点统一走 POST + 手动 InputStream 消费（与 AiChat / AiApprovalAdvice 同款）。
     *
     * <p>工具执行经 {@link ContextExecutor#capture} 投递到上下文感知执行器：
     * 异步既是为了不长期占用请求线程，更是为了使 ScopedValue 上下文（{@code LoginContext}）可在 worker 线程重绑，
     * 否则守卫读不到登录用户会把调用判成未登录。
     */
    @Operation(summary = "SSE 流式调用工具（与 REST/MCP 同源守卫；帧：meta → result|error → done）")
    @PostMapping(value = "/call/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter callStream(@Validated @RequestBody ToolCallRequest request) {
        // 0 = 不超时：长任务由工具执行结束或连接断开驱动完成（与 AiApprovalAdvice 同款）
        SseEmitter emitter = new SseEmitter(0L);
        String argsJson = request.getArgs() == null ? "{}" : JSON.toJSONString(request.getArgs());
        String toolName = request.getToolName();
        contextExecutor.execute(ContextExecutor.capture(() -> {
            try {
                new ToolCallStreamer(aiToolService).stream(toolName, argsJson, frame -> {
                    try {
                        emitter.send(SseEmitter.event().name(frame.name()).data(frame.data()));
                    } catch (IOException | IllegalStateException e) {
                        // 客户端中途断开：连接已不可写，记录后收口（不向上抛出，见 ToolCallStreamer 口径）
                        log.warn("[PivotOS] AI 工具 SSE 帧下发失败（客户端可能已断开）：tool={} frame={} err={}",
                                toolName, frame.name(), e.getMessage());
                    }
                });
            } finally {
                emitter.complete();
            }
        }));
        return emitter;
    }
}

package com.pivotos.ai.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.enums.ToolInvokeStatus;
import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 守卫式工具回调装饰器（S98 A2）：包裹原始 ToolCallback，执行前过三道闸。
 *
 * <p>执行顺序：
 * <ol>
 *   <li>注册检查——ai_tool 无记录（启动同步器未登记）拒绝；</li>
 *   <li>权限检查——停用拒绝 + 角色白名单校验（{@link AiToolGuardService}）；</li>
 *   <li>二次确认预检——confirm_required 工具入参缺 confirm=true 时不执行，
 *       返回预检说明供模型向用户复述（need_confirm 审计）。</li>
 * </ol>
 * 每次调用全量落审计（成功/失败/拒绝/预检），拒绝与预检均以文本结果返回
 * 而非抛异常——MCP tools/call 与 ChatClient function calling 两条链路语义一致。
 */
public class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

    /** 写操作二次确认入参键（协议约定：confirm=true 才真实执行） */
    private static final String CONFIRM_ARG_KEY = "confirm";

    private final ToolCallback delegate;
    private final AiToolGuardService guardService;
    private final AiToolInvokeRecorder recorder;

    public GuardedToolCallback(ToolCallback delegate, AiToolGuardService guardService, AiToolInvokeRecorder recorder) {
        this.delegate = delegate;
        this.guardService = guardService;
        this.recorder = recorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return doCall(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doCall(toolInput, toolContext);
    }

    private String doCall(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        long start = System.currentTimeMillis();
        // 闸 1：注册检查（同步器未登记的工具不放行）
        AiToolGuardService.ToolSnapshot snapshot = guardService.snapshot(toolName);
        if (snapshot == null) {
            recorder.record(toolName, toolInput, ToolInvokeStatus.FORBIDDEN, "工具未注册", elapsed(start));
            return "工具未注册，已被拒绝调用：" + toolName;
        }
        // 闸 2：停用 + 角色白名单
        LoginUser loginUser = LoginContext.get();
        try {
            guardService.checkAllowed(snapshot, loginUser);
        } catch (ToolForbiddenException e) {
            recorder.record(toolName, toolInput, ToolInvokeStatus.FORBIDDEN, e.getMessage(), elapsed(start));
            return e.getMessage();
        }
        // 闸 3：写操作二次确认预检
        if (isConfirmRequired(snapshot) && !isConfirmed(toolInput)) {
            String preview = "【预检】工具「" + toolName + "」为写操作，尚未确认执行。"
                    + "请向用户复述本操作影响，获得明确同意后以 confirm=true 重新调用。";
            recorder.record(toolName, toolInput, ToolInvokeStatus.NEED_CONFIRM, null, elapsed(start));
            return preview;
        }
        // 放行：执行原始回调并审计
        try {
            String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            recorder.record(toolName, toolInput, ToolInvokeStatus.SUCCESS, null, elapsed(start));
            return result;
        } catch (Exception e) {
            log.warn("[PivotOS] AI 工具执行失败：tool={} err={}", toolName, e.getMessage());
            recorder.record(toolName, toolInput, ToolInvokeStatus.FAIL, e.getMessage(), elapsed(start));
            return "工具执行失败：" + e.getMessage();
        }
    }

    private boolean isConfirmRequired(AiToolGuardService.ToolSnapshot snapshot) {
        Integer flag = snapshot.tool().getConfirmRequired();
        return flag != null && flag == 1;
    }

    /**
     * 解析入参 JSON 的 confirm 标记；解析失败按未确认口径（从严）
     */
    private boolean isConfirmed(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return false;
        }
        try {
            JSONObject args = JSON.parseObject(toolInput);
            return args != null && Boolean.TRUE.equals(args.getBoolean(CONFIRM_ARG_KEY));
        } catch (Exception e) {
            return false;
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

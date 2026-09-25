package com.pivotos.ai.tool;

import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;

/**
 * 守卫式工具回调汇聚器（S98 A2）：把原始 ToolCallbackProvider 的全部回调
 * 逐个包裹为 {@link GuardedToolCallback}，成为 MCP 端点与对话 tools 的唯一供给。
 *
 * <p>装饰器模式不侵入 MethodToolCallbackProvider 的扫描装配语义——
 * AiToolCallbackConfiguration 仍是「单点汇聚全部工具」的入口，本类只加闸。
 */
public class GuardedToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final AiToolGuardService guardService;
    private final AiToolInvokeRecorder recorder;

    public GuardedToolCallbackProvider(ToolCallbackProvider delegate,
                                       AiToolGuardService guardService,
                                       AiToolInvokeRecorder recorder) {
        this.delegate = delegate;
        this.guardService = guardService;
        this.recorder = recorder;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> (ToolCallback) new GuardedToolCallback(callback, guardService, recorder))
                .toArray(ToolCallback[]::new);
    }
}

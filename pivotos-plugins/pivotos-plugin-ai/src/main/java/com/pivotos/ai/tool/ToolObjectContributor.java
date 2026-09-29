package com.pivotos.ai.tool;

/**
 * AI 工具对象扩展点（A4-3 / S112）。
 *
 * <p>背景：A2 工具体系的装配点在 plugin-ai（{@code AiToolCallbackConfiguration}），
 * 而「读写工程源码」的能力归属 ai-coding 插件（那里才有代码索引门面与落盘白名单）。
 * 架构边界要求 Plugin 实现之间不得互赖（只依赖 -api），而依赖方向恰好是
 * {@code ai-coding → ai}，plugin-ai 侧不能反向引用 ai-coding 的实现类，
 * 因此由下游插件通过本扩展点**主动登记**自己的 @Tool 工具对象。
 *
 * <p>收益：登记进来的工具自动获得 A2 全套守卫——注册闸（ai_tool 未登记即拒）、
 * 停用与角色白名单、写操作 confirm 二次确认预检、全量调用审计，
 * 且同时出现在 REST（{@code /ai/tool/invoke}）、MCP 与 ChatClient function calling 三条链路上。
 *
 * @author PivotOS
 * @since 2.14.0（S112 A4-3）
 */
public interface ToolObjectContributor {

    /**
     * 本插件要登记的工具对象（其 {@code @Tool} 方法会被扫描成 ToolCallback）。
     *
     * @return 工具对象数组；返回空数组表示当前无可登记工具（如开关未开）
     */
    Object[] toolObjects();
}

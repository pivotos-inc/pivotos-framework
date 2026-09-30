package com.pivotos.ai.tool;

/**
 * A2 守卫链路的「失败/拦截信号」文案单一事实源（A5-1 / S116 提取）。
 *
 * <p>背景（S112 既有设计，勿改）：{@link GuardedToolCallback} 为了保证
 * MCP {@code tools/call}、REST {@code /ai/tool/call}、ChatClient function calling
 * 三条链路语义一致，**把业务异常吞成文本返回**，HTTP 层仍是 <code>code=0</code>：
 * <ul>
 *   <li>未注册 / 停用 / 无登录上下文 / 越权 → 拒绝文案；</li>
 *   <li>未带 {@code confirm=true} 的写工具 → 预检文案；</li>
 *   <li>执行抛异常 → 失败文案。</li>
 * </ul>
 *
 * <p>这带来一个执行侧的硬问题：**调用方无法从返回类型上区分成败**（全都是 String）。
 * 早期各消费方只能凭这段文案做散落各处的 {@code startsWith} 判断，属于典型的
 * 「同源字符串多份副本」漂移风险。本类把这些前置信号常量化：
 * <ul>
 *   <li>守卫侧（{@link GuardedToolCallback} / {@link AiToolGuardServiceImpl}）用常量拼文案，行为逐字不变；</li>
 *   <li>A5-1 编排执行器用 {@link #isFailure(String)} 与 {@link #isNeedConfirm(String)}
 *       做确定性成败判定，从而实现「失败即停」与「写步骤拦停」。</li>
 * </ul>
 * 用例由 {@code ToolGuardSignalTest} 与 {@code AiToolCallbackConfigurationTest} 双向锁死。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
public final class ToolGuardSignal {

    private ToolGuardSignal() {
    }

    /** 工具未在 ai_tool 注册（同步器未登记） */
    public static final String UNREGISTERED = "工具未注册，已被拒绝调用：";

    /** 工具已停用（ai_tool.status=1） */
    public static final String DISABLED = "工具已停用：";

    /** 缺少登录上下文（ScopedValue 作用域外调用） */
    public static final String LOGIN_REQUIRED = "工具调用需登录上下文：";

    /** 角色白名单未命中 */
    public static final String FORBIDDEN = "当前角色无权调用工具：";

    /** 写工具未携带 confirm=true 的预检前缀 */
    public static final String PREVIEW = "【预检】";

    /** 工具本体执行失败 */
    public static final String EXEC_FAILED = "工具执行失败：";

    /**
     * 返回文本是否为「未获执行」的终态信号（注册闸/权限闸/执行失败任一命中）。
     *
     * @param result 工具回调返回文本
     * @return true 表示该步骤未成功执行，编排应失败即停（S116 执行器口径）
     */
    public static boolean isFailure(String result) {
        if (result == null) {
            return true;
        }
        return startsWithAny(result, UNREGISTERED, DISABLED, LOGIN_REQUIRED, FORBIDDEN, EXEC_FAILED);
    }

    /**
     * 返回文本是否为「写操作待确认」的预检回执（需要用户二次确认后重放）。
     *
     * <p>注意：预检不是失败——它是协议内的正常分支，编排停在写步骤上等待确认，
     * 而不是记 FAIL。两者混同会让「待确认」被误判成链路故障。
     */
    public static boolean isNeedConfirm(String result) {
        return result != null && result.startsWith(PREVIEW);
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

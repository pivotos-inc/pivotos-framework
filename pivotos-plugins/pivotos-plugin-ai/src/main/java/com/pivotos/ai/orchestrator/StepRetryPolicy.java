package com.pivotos.ai.orchestrator;

import com.pivotos.ai.tool.ToolGuardSignal;

/**
 * 编排步骤的「可重试性」判定（A5-2 / S117）——确定性规则，不看异常类型、不做文案模糊匹配。
 *
 * <p><b>为什么只能按信号类别判定</b>：A2 的 {@code GuardedToolCallback} 为了保证
 * MCP / REST / ChatClient 三链路语义一致，把业务异常吞成文本返回（HTTP 层恒 code=0）。
 * 编排侧拿到的只有一个 String，既没有异常类型也没有状态码，唯一可用的判据是
 * {@link ToolGuardSignal} 已常量化的信号前缀。
 *
 * <p><b>三类信号的可重试性</b>：
 * <ul>
 *   <li>{@code EXEC_FAILED}（工具本体执行失败）——唯一可能是瞬时故障的类别，可重试；</li>
 *   <li>{@code UNREGISTERED} / {@code DISABLED} / {@code LOGIN_REQUIRED} / {@code FORBIDDEN}
 *       ——配置态与权限态，重试必然再失败，一次都不重试；</li>
 *   <li>{@code PREVIEW}（写操作待确认）——协议内的正常分支，不是失败，编排停在写步骤上等确认。</li>
 * </ul>
 *
 * <p><b>写工具为什么默认零重试（本类最硬的一条）</b>：守卫把异常吞成文本，意味着
 * 「失败发生在副作用之前还是之后」不可判定；而 {@code sendInboxMessage}（发站内信）与
 * {@code urgeFlowInstance}（催办）<b>都不是幂等操作</b>——S117 开工实测：连续调用两次
 * {@code sendInboxMessage}，站内消息总数 408 → 410（两个不同 messageId）。
 * 因此写步骤重试会实实在在地重复副作用，这里<b>默认写工具一律不重试</b>，
 * 即便 {@code retry.write-enabled} 被显式打开也只对「工具自身声明幂等」的场景负责（当前不启用）。
 *
 * @author PivotOS
 * @since 2.15.0（S117 A5-2）
 */
public final class StepRetryPolicy {

    private StepRetryPolicy() {
    }

    /**
     * 判定该步的失败是否值得重试。
     *
     * @param output 工具返回文本
     * @param write  该步是否为写操作
     * @param allowWriteRetry 配置是否允许写步骤重试（默认 false）
     * @return true 表示可再试一次
     */
    public static boolean retryable(String output, boolean write, boolean allowWriteRetry) {
        if (write && !allowWriteRetry) {
            return false;
        }
        if (ToolGuardSignal.isNeedConfirm(output)) {
            return false;
        }
        if (!ToolGuardSignal.isFailure(output)) {
            return false;
        }
        return isExecFailed(output);
    }

    /**
     * 是否为「工具本体执行失败」——五类终态信号里唯一可能瞬时的一类。
     */
    public static boolean isExecFailed(String output) {
        return output != null && output.startsWith(ToolGuardSignal.EXEC_FAILED);
    }
}

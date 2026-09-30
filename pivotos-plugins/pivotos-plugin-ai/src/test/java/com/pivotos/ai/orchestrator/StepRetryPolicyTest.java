package com.pivotos.ai.orchestrator;

import com.pivotos.ai.tool.ToolGuardSignal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编排步骤可重试性判定用例（A5-2 / S117）。
 *
 * <p>本用例锁死的是「写工具默认零重试」这条硬约束——它背后是 S117 开工实测：
 * 连续两次 {@code sendInboxMessage}，站内消息总数 408 → 410（两个不同 messageId）。
 * 写工具非幂等，重试即重复副作用，这条约束一旦被放开就是线上事故。
 */
class StepRetryPolicyTest {

    private static final String EXEC_FAILED = ToolGuardSignal.EXEC_FAILED + "连接超时";

    @Test
    @DisplayName("工具本体执行失败：只读步骤可重试")
    void execFailedOnReadIsRetryable() {
        Assertions.assertTrue(StepRetryPolicy.retryable(EXEC_FAILED, false, false));
    }

    @Test
    @DisplayName("工具本体执行失败：写步骤默认不重试（非幂等，重试会重复副作用）")
    void execFailedOnWriteIsNotRetryableByDefault() {
        Assertions.assertFalse(StepRetryPolicy.retryable(EXEC_FAILED, true, false));
    }

    @Test
    @DisplayName("显式打开写重试开关才对写步骤重试（当前不启用，仅锁死开关语义）")
    void writeRetryOnlyWhenExplicitlyEnabled() {
        Assertions.assertTrue(StepRetryPolicy.retryable(EXEC_FAILED, true, true));
    }

    @Test
    @DisplayName("四类终态信号一次都不重试：未注册 / 停用 / 无登录 / 无权限")
    void terminalSignalsNeverRetry() {
        Assertions.assertFalse(StepRetryPolicy.retryable(ToolGuardSignal.UNREGISTERED + "x", false, false));
        Assertions.assertFalse(StepRetryPolicy.retryable(ToolGuardSignal.DISABLED + "x", false, false));
        Assertions.assertFalse(StepRetryPolicy.retryable(ToolGuardSignal.LOGIN_REQUIRED + "x", false, false));
        Assertions.assertFalse(StepRetryPolicy.retryable(ToolGuardSignal.FORBIDDEN + "x", false, false));
    }

    @Test
    @DisplayName("写操作预检回执不是失败：既不重试也不算失败")
    void previewIsNotRetryable() {
        Assertions.assertFalse(StepRetryPolicy.retryable(ToolGuardSignal.PREVIEW + "待确认", false, false));
    }

    @Test
    @DisplayName("成功输出不重试")
    void successIsNotRetryable() {
        Assertions.assertFalse(StepRetryPolicy.retryable("{\"total\":1}", false, false));
    }

    @Test
    @DisplayName("只有 EXEC_FAILED 属于「工具本体执行失败」")
    void onlyExecFailedIsTransient() {
        Assertions.assertTrue(StepRetryPolicy.isExecFailed(EXEC_FAILED));
        Assertions.assertFalse(StepRetryPolicy.isExecFailed(ToolGuardSignal.FORBIDDEN + "x"));
        Assertions.assertFalse(StepRetryPolicy.isExecFailed(null));
    }
}

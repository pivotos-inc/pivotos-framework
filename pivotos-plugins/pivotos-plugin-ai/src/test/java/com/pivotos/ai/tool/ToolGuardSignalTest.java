package com.pivotos.ai.tool;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A2 守卫信号用例（S116 提取 ToolGuardSignal 时补）——锁死「成败判定」的唯一事实源。
 *
 * <p>背景：{@link GuardedToolCallback} 把业务异常吞成文本返回（MCP/REST/ChatClient 三链路
 * 语义一致的需要），导致调用侧只能从文案区分「成功 / 拒绝 / 待确认 / 失败」。
 * A5-1 编排执行器的「失败即停」依赖这套判定，故用用例把文案前缀钉死——
 * 一旦有人调整守卫文案，这里会先红，而不是让编排静默地把失败当成功继续跑下去。
 */
class ToolGuardSignalTest {

    @Test
    @DisplayName("五类终态信号：未注册/停用/无登录/越权/执行失败均判失败")
    void failureSignals() {
        Assertions.assertTrue(ToolGuardSignal.isFailure(ToolGuardSignal.UNREGISTERED + "x"));
        Assertions.assertTrue(ToolGuardSignal.isFailure(ToolGuardSignal.DISABLED + "x"));
        Assertions.assertTrue(ToolGuardSignal.isFailure(ToolGuardSignal.LOGIN_REQUIRED + "x"));
        Assertions.assertTrue(ToolGuardSignal.isFailure(ToolGuardSignal.FORBIDDEN + "x"));
        Assertions.assertTrue(ToolGuardSignal.isFailure(ToolGuardSignal.EXEC_FAILED + "boom"));
    }

    @Test
    @DisplayName("预检文案是「待确认」不是「失败」——两者混同会让待确认被误判成链路故障")
    void previewIsNotFailure() {
        String preview = ToolGuardSignal.PREVIEW + "工具「urgeFlowInstance」为写操作，尚未确认执行。";
        Assertions.assertTrue(ToolGuardSignal.isNeedConfirm(preview));
        Assertions.assertFalse(ToolGuardSignal.isFailure(preview));
    }

    @Test
    @DisplayName("正常业务回执既不失败也不待确认")
    void normalOutput() {
        Assertions.assertFalse(ToolGuardSignal.isFailure("{\"total\":1}"));
        Assertions.assertFalse(ToolGuardSignal.isFailure("催办通知已发送"));
        Assertions.assertFalse(ToolGuardSignal.isNeedConfirm("{\"total\":1}"));
    }

    @Test
    @DisplayName("空返回按失败处理（工具没给出任何回执不能算成功）")
    void nullIsFailure() {
        Assertions.assertTrue(ToolGuardSignal.isFailure(null));
        Assertions.assertFalse(ToolGuardSignal.isNeedConfirm(null));
    }
}

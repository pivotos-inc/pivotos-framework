package com.pivotos.ai.orchestrator;

import com.pivotos.ai.domain.vo.ApprovalReferenceVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 受控自动预审低风险规则用例（A4E / S117）。
 *
 * <p>锁死三件事：
 * <ol>
 *   <li><b>默认关闭必须硬生效</b>——开关未开时，哪怕输入完全合规也必须不放行；</li>
 *   <li><b>零误放行</b>——任一规则不命中即不放行，且 reason 要能说清是哪一条；</li>
 *   <li><b>判定确定性</b>——同输入两次判定结果必须全等（不调用 LLM、不依赖时钟与随机）。</li>
 * </ol>
 */
class AutoApprovalPolicyTest {

    private static List<ApprovalReferenceVO> refs() {
        ApprovalReferenceVO ref = new ApprovalReferenceVO();
        ref.setChunkId(1L);
        ref.setQuote("请假 3 天以内由直属主管审批");
        return List.of(ref);
    }

    /** 全部规则都满足的输入（除开关外） */
    private static AutoApprovalPolicy.Input base(boolean enabled) {
        return new AutoApprovalPolicy.Input(enabled, true, true, "approve", refs(), true,
                1, "1", Map.of("days", 2), "", 0L);
    }

    @Test
    @DisplayName("开关关闭时：输入再合规也不放行（默认关闭必须硬生效）")
    void disabledNeverPasses() {
        AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(base(false));
        Assertions.assertFalse(decision.pass());
        Assertions.assertTrue(decision.reason().contains("未启用"));
        Assertions.assertTrue(decision.ruleHits().isEmpty());
    }

    @Test
    @DisplayName("规则全中：放行，并给出命中明细")
    void allRulesHitPasses() {
        AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(base(true));
        Assertions.assertTrue(decision.pass());
        Assertions.assertEquals(6, decision.ruleHits().size());
        Assertions.assertTrue(decision.ruleHits().contains(AutoApprovalPolicy.R_CONCLUSION));
        Assertions.assertTrue(decision.ruleHits().contains(AutoApprovalPolicy.R_SINGLE_APPROVER));
    }

    @Test
    @DisplayName("结论非通过一律不放行（reject / need_info 不能自动通过）")
    void nonApproveConclusionRejected() {
        for (String conclusion : List.of("reject", "need_info", "unknown")) {
            AutoApprovalPolicy.Input in = new AutoApprovalPolicy.Input(true, true, true, conclusion,
                    refs(), true, 1, "1", Map.of(), "", 0L);
            AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(in);
            Assertions.assertFalse(decision.pass(), conclusion + " 不应放行");
            Assertions.assertTrue(decision.reason().contains("建议结论"));
        }
    }

    @Test
    @DisplayName("无制度依据引用一律不放行（模型没找到依据就不许自动通过）")
    void noReferenceRejected() {
        AutoApprovalPolicy.Input in = new AutoApprovalPolicy.Input(true, true, true, "approve",
                List.of(), true, 1, "1", Map.of(), "", 0L);
        AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(in);
        Assertions.assertFalse(decision.pass());
        Assertions.assertTrue(decision.reason().contains("制度依据引用"));
    }

    @Test
    @DisplayName("依据来自非制度类知识库：要求制度库时不放行")
    void nonPolicyKbRejected() {
        AutoApprovalPolicy.Input in = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), false, 1, "1", Map.of(), "", 0L);
        Assertions.assertFalse(AutoApprovalPolicy.evaluate(in).pass());
    }

    @Test
    @DisplayName("会签/票签（多审批人）不放行：自动通过不等于代投一票")
    void multiApproverRejected() {
        AutoApprovalPolicy.Input in = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), true, 2, "1", Map.of(), "", 0L);
        AutoApprovalPolicy.Decision decision = AutoApprovalPolicy.evaluate(in);
        Assertions.assertFalse(decision.pass());
        Assertions.assertTrue(decision.reason().contains("单审批人"));
    }

    @Test
    @DisplayName("退回态实例不放行")
    void rejectedInstanceRejected() {
        AutoApprovalPolicy.Input in = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), true, 1, "9", Map.of(), "", 0L);
        Assertions.assertFalse(AutoApprovalPolicy.evaluate(in).pass());
    }

    @Test
    @DisplayName("金额阈值：超阈值不放行；变量缺失从严按不通过处理")
    void amountThreshold() {
        AutoApprovalPolicy.Input over = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), true, 1, "1", Map.of("amount", 5000), "amount", 1000L);
        Assertions.assertFalse(AutoApprovalPolicy.evaluate(over).pass());

        AutoApprovalPolicy.Input missing = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), true, 1, "1", Map.of(), "amount", 1000L);
        AutoApprovalPolicy.Decision missingDecision = AutoApprovalPolicy.evaluate(missing);
        Assertions.assertFalse(missingDecision.pass());
        Assertions.assertTrue(missingDecision.reason().contains("缺失"));

        AutoApprovalPolicy.Input within = new AutoApprovalPolicy.Input(true, true, true, "approve",
                refs(), true, 1, "1", Map.of("amount", "800"), "amount", 1000L);
        Assertions.assertTrue(AutoApprovalPolicy.evaluate(within).pass(), "字符串金额应可解析且放行");
    }

    @Test
    @DisplayName("判定确定性：同输入两次结果全等（不依赖 LLM / 时钟 / 随机）")
    void deterministic() {
        AutoApprovalPolicy.Input in = base(true);
        AutoApprovalPolicy.Decision first = AutoApprovalPolicy.evaluate(in);
        AutoApprovalPolicy.Decision second = AutoApprovalPolicy.evaluate(in);
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(first.ruleHits(), second.ruleHits());
    }
}

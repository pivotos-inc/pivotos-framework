package com.pivotos.ai.orchestrator;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.domain.vo.ApprovalReferenceVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受控自动预审的「低风险单」判定（A4E / S117）——<b>全确定性规则，不问 LLM</b>。
 *
 * <p>为什么不能让 LLM 自评「是否低风险」：与 A4「仲裁不交给 LLM」（S110）、
 * 「diff 确定性渲染」（S111）同口径——模型自评置信度本身就是失效载体，
 * 而本判定的输入（结论三态 / 引用是否为空 / 审批人数 / 实例状态 / 变量阈值）全是可判定量。
 *
 * <p>七条规则<b>全部命中才放行</b>，任一不命中即不放行（零误放行优先于覆盖率）：
 * <ol>
 *   <li>开关已启用；</li>
 *   <li>建议结论为 {@code approve}（reject / need_info 一律不动）；</li>
 *   <li>有制度依据引用（无引用说明模型也没找到依据，绝不放行）；</li>
 *   <li>制度依据来自制度类知识库（{@code requirePolicyKb} 开启时）；</li>
 *   <li>当前待办为单审批人（{@code requireSingleApprover} 开启时，排除会签/票签）；</li>
 *   <li>实例非退回态（flow_status != 9）；</li>
 *   <li>金额变量在阈值内（配置了 {@code amountVariableKey} 时；变量缺失从严不通过）。</li>
 * </ol>
 *
 * <p>本类是纯静态函数，不依赖 Spring，便于单测锁死规则矩阵（同输入两次输出必须全等）。
 */
public final class AutoApprovalPolicy {

    private AutoApprovalPolicy() {
    }

    /** 判定输入（全为可判定量，不含任何模型自述） */
    public record Input(boolean enabled,
                        boolean requirePolicyKb,
                        boolean requireSingleApprover,
                        String conclusion,
                        List<ApprovalReferenceVO> references,
                        boolean policyKb,
                        Integer approverCount,
                        String flowStatus,
                        Map<String, Object> variables,
                        String amountVariableKey,
                        long maxAmount) {
    }

    /** 判定输出 */
    public record Decision(boolean pass, String reason, List<String> ruleHits) {

        public static Decision rejected(String reason, List<String> hits) {
            return new Decision(false, reason, hits);
        }

        public static Decision passed(List<String> hits) {
            return new Decision(true, String.join("；", hits), hits);
        }
    }

    /** 规则名常量（落 auto_rule_hits，审计要能读出「命中/未命中哪一条」） */
    public static final String R_ENABLED = "开关已启用";
    public static final String R_CONCLUSION = "建议结论为通过";
    public static final String R_REFERENCE = "存在制度依据引用";
    public static final String R_POLICY_KB = "制度依据来自制度类知识库";
    public static final String R_SINGLE_APPROVER = "当前待办为单审批人";
    public static final String R_NOT_REJECTED = "实例非退回态";
    public static final String R_AMOUNT = "金额在阈值内";

    /** 退回态状态码（warm-flow flow_status=9） */
    private static final String FLOW_STATUS_REJECTED = "9";

    public static Decision evaluate(Input in) {
        List<String> hits = new ArrayList<>();
        if (!in.enabled()) {
            return Decision.rejected(AiErrorCode.APPROVAL_AUTO_DISABLED.getMsg(), hits);
        }
        hits.add(R_ENABLED);

        if (!"approve".equals(in.conclusion())) {
            return Decision.rejected("建议结论非通过（" + in.conclusion() + "），不满足 " + R_CONCLUSION, hits);
        }
        hits.add(R_CONCLUSION);

        if (in.references() == null || in.references().isEmpty()) {
            return Decision.rejected("无制度依据引用，不满足 " + R_REFERENCE, hits);
        }
        hits.add(R_REFERENCE);

        if (in.requirePolicyKb() && !in.policyKb()) {
            return Decision.rejected("制度依据未来自制度类知识库，不满足 " + R_POLICY_KB, hits);
        }
        if (in.requirePolicyKb()) {
            hits.add(R_POLICY_KB);
        }

        if (in.requireSingleApprover() && !Integer.valueOf(1).equals(in.approverCount())) {
            return Decision.rejected("当前待办非单审批人（approverCount=" + in.approverCount()
                    + "），不满足 " + R_SINGLE_APPROVER, hits);
        }
        if (in.requireSingleApprover()) {
            hits.add(R_SINGLE_APPROVER);
        }

        if (FLOW_STATUS_REJECTED.equals(in.flowStatus())) {
            return Decision.rejected("实例处于退回态，不满足 " + R_NOT_REJECTED, hits);
        }
        hits.add(R_NOT_REJECTED);

        if (in.amountVariableKey() != null && !in.amountVariableKey().isBlank()) {
            Long amount = readAmount(in.variables(), in.amountVariableKey());
            if (amount == null) {
                return Decision.rejected("金额变量「" + in.amountVariableKey()
                        + "」缺失或不可解析，从严按不满足 " + R_AMOUNT + " 处理", hits);
            }
            if (amount > in.maxAmount()) {
                return Decision.rejected("金额 " + amount + " 超过阈值 " + in.maxAmount()
                        + "，不满足 " + R_AMOUNT, hits);
            }
            hits.add(R_AMOUNT);
        }
        return Decision.passed(hits);
    }

    /** 金额读取：数值型直接取，字符串尝试解析；解析不出返回 null（从严） */
    private static Long readAmount(Map<String, Object> variables, String key) {
        if (variables == null) {
            return null;
        }
        Object value = variables.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

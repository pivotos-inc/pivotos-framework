package com.pivotos.ai.orchestrator;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A4E 审批建议增强配置（S117）：受控自动预审。
 *
 * <p>入口：<code>pivotos.ai.approval.auto-approve.*</code>。
 * <b>默认全关</b>——自动通过是「AI 代审批人完成审批动作」，能力必须显式开启，
 * 与 {@code pivotos.ai.orchestrator.enabled} / {@code pivotos.ai.coding.modify.enabled}
 * 同属「能力显式开启」口径。
 *
 * <p>注意：{@code pivotos.ai.*} 缩进挂错一级会静默失效（不报错也不生效），
 * 因此 {@code auto-approve} 必须挂在 {@code approval} 之下、与 {@code default-kb-id} 同级。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pivotos.ai.approval.auto-approve")
public class ApprovalAdviceProperties {

    /**
     * 受控自动预审是否启用（默认关闭）。
     *
     * <p>关闭时任何待办都不会被自动通过，且会把「未启用」作为确定性原因留痕，
     * 便于事后区分「没开」与「开了但没命中规则」。
     */
    private boolean enabled = false;

    /** 是否要求制度依据必须来自「制度类知识库」（默认要求） */
    private boolean requirePolicyKb = true;

    /** 是否要求单审批人（默认要求：会签/票签节点下自动通过等于 AI 代投一票，必须排除） */
    private boolean requireSingleApprover = true;

    /** 金额变量名（为空表示不校验金额阈值） */
    private String amountVariableKey = "";

    /** 金额上限（仅当 amountVariableKey 非空时生效；变量缺失从严按不通过处理） */
    private long maxAmount = 0L;

    /** 自动通过写入的审批意见（进审批历史，便于追溯「这条是 AI 预审通过的」） */
    private String message = "AI 预审自动通过（依据制度检索结论，审批人已授权自动预审）";
}

package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 审批建议实体（S101 A3）
 *
 * <p>每次审批助手建议生成落一条：防篡改存模型原文（rawContent），
 * 结构化解析结果（conclusion/reason/referencesJson）另行落便于查询展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_approval_advice")
public class AiApprovalAdvice extends TenantBaseDO {

    /** 待办任务 ID（warm-flow flow_task.id） */
    private Long taskId;

    /** 流程实例 ID（warm-flow flow_instance.id） */
    private Long instanceId;

    /** 请求人 ID（当前审批人） */
    private Long userId;

    /** 检索所用知识库 ID（未检索到/无默认库为 null） */
    private Long kbId;

    /** 结论（approve 建议通过 / reject 建议驳回 / need_info 需补充材料） */
    private String conclusion;

    /** 是否受控自动通过（A4E / S117；0 未通过 1 已自动通过） */
    private Integer autoPassed;

    /** 自动预审判定原因（未启用 / 未命中规则 / 命中明细） */
    private String autoDecisionReason;

    /** 低风险规则命中明细（JSON 数组） */
    private String autoRuleHits;

    /** 结论理由（模型输出；结构化解析失败时为原文降级） */
    private String reason;

    /** 制度依据引用（JSON 数组：[{"chunkId","fileName","quote"}]，无引用为 []） */
    private String referencesJson;

    /** 模型输出原文（防篡改留痕，解析失败也落原文） */
    private String rawContent;

    /** 供应商编码（ai_provider.provider_code，便于用量归因） */
    private String provider;

    /** 模型名（本次生成实际使用的模型） */
    private String model;

    /** 生成耗时（毫秒） */
    private Long costMs;
}

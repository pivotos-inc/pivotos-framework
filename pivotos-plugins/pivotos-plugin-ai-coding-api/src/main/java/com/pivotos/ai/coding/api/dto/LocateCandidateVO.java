package com.pivotos.ai.coding.api.dto;

import lombok.Data;

/**
 * 定位候选（粗筛阶段产物）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Data
public class LocateCandidateVO {

    /** 仓库根相对路径 */
    private String path;

    /** 所属模块 */
    private String module;

    /** 分层标签（分层约定感知） */
    private String layer;

    /** 主类型名 */
    private String typeName;

    /** 候选来源：llm（粗筛模型）/ keyword（确定性关键词召回） */
    private String source;

    /** 置信度（LLM 自评 0~1；关键词召回为归一化分值） */
    private Double confidence;

    /** 入选理由 */
    private String reason;
}

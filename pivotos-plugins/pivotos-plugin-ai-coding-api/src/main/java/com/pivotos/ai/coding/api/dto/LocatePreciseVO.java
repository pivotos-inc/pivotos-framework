package com.pivotos.ai.coding.api.dto;

import lombok.Data;

/**
 * 精定位结果（单候选）。
 *
 * <p>{@code score} 为仲裁打分（确定性合成，非 LLM 自评），{@code chosen} 由 score 取最大者；
 * 三个分项一并回传，便于验收时复盘仲裁为何这样选。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Data
public class LocatePreciseVO {

    /** 仓库根相对路径 */
    private String path;

    /** 分层标签 */
    private String layer;

    /** 目标方法/区块名 */
    private String method;

    /** 建议改动起始行（1 基） */
    private Integer startLine;

    /** 建议改动结束行（1 基） */
    private Integer endLine;

    /** 该候选是否确为改动落点（LLM 判定 false 时仲裁直接淘汰） */
    private Boolean applicable;

    /** LLM 精定位自评置信度 0~1 */
    private Double confidence;

    /** 理由 */
    private String reason;

    /** 确定性信号：关键词在符号表/路径的命中率 0~1 */
    private Double symbolHit;

    /** 确定性信号：分层因子归一值 0~1 */
    private Double layerFactor;

    /** 仲裁综合分（越大越优） */
    private Double score;
}

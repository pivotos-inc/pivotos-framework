package com.pivotos.ai.coding.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 两段定位结果（粗筛候选 → 并行精定位 → 确定性仲裁）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Data
public class LocateResultVO {

    /** 目标仓库逻辑名 */
    private String repo;

    /** 原始改动意图 */
    private String intent;

    /** 实际使用的模型（模型覆盖生效时为覆盖值） */
    private String model;

    /** 意图解析结果：domain / change_type / keywords */
    private Map<String, Object> parse;

    /** 参与精定位的候选（LLM 粗筛 ∪ 确定性关键词召回，去重） */
    private List<LocateCandidateVO> candidates;

    /** 各候选的精定位结果（并行产出，按 score 降序） */
    private List<LocatePreciseVO> precise;

    /** 仲裁选中的落点 */
    private LocatePreciseVO chosen;

    /** 是否降级选中（全部候选被判为不适用时，退回置信度最高者） */
    private Boolean fallback;

    /** 索引文件数（便于验收时确认索引规模） */
    private Integer indexSize;

    /** 端到端耗时（毫秒） */
    private Long costMs;
}

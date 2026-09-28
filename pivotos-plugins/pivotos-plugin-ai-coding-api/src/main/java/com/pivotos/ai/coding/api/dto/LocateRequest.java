package com.pivotos.ai.coding.api.dto;

import lombok.Data;

/**
 * 代码定位请求（A4-1 两段定位端点入参）。
 *
 * <p>{@code model} 为模型覆盖位：留空走供应商默认模型，指定则用于同一意图集的双模型对比
 * （S110 决策项「qwen-plus vs qwen3.5-plus」实测选型），仅在本次请求内生效。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Data
public class LocateRequest {

    /** 改动意图（自然语言，由服务层校验非空） */
    private String intent;

    /** 目标仓库逻辑名（配置中的 repos[].name，默认 fw） */
    private String repo = "fw";

    /** 模型覆盖（可选；留空走供应商默认模型） */
    private String model;

    /** 粗筛候选数（可选；留空走配置默认 topN） */
    private Integer topN;

    /** 是否强制重建索引（源码变更后刷新） */
    private Boolean rebuild;
}

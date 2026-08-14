package com.pivotos.ai.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 供应商视图对象（管理页） */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProviderVO extends BaseDTO {

    /** 供应商名称 */
    private String name;

    /** 供应商编码 */
    private String code;

    /** OpenAI 兼容 base-url */
    private String baseUrl;

    /** 默认模型 */
    private String defaultModel;

    /** 向量化模型名（空则回退 spring.ai.openai.embedding.options.model） */
    private String embeddingModel;

    /** 重排模型名（如 qwen3-rerank，空则不启用重排，S65） */
    private String rerankModel;

    /** 排序 */
    private Integer sort;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 租户ID（0=平台/默认租户，管理页区分平台配置与租户自有配置） */
    private Long tenantId;

    /** 启用中的 Key 数量（管理页概览） */
    private Long activeKeyCount;
}

package com.pivotos.ai.domain.vo;

import lombok.Data;

/**
 * AI 用量按供应商 × Key 聚合 VO（S92）
 */
@Data
public class AiUsageProviderVO {

    /** 供应商编码（静态兜底为 static） */
    private String providerCode;

    /** Key ID（静态兜底为 null） */
    private Long keyId;

    /** Key 备注名（服务层回填，免前端再查） */
    private String keyLabel;

    /** 调用次数 */
    private Long calls;

    /** 提示词 token 合计 */
    private Long promptTokens;

    /** 生成 token 合计 */
    private Long completionTokens;

    /** 总 token 合计 */
    private Long totalTokens;
}

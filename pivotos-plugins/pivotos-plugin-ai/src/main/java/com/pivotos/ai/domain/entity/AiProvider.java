package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 模型供应商实体（OpenAI 兼容协议，base_url 须含 /v1）。
 * 多租户：继承 TenantBaseDO 参与行级隔离，tenant_id=0 为平台/默认租户，
 * 解析链按「租户自有配置优先 → 平台兜底」取用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_provider")
public class AiProvider extends TenantBaseDO {

    /** 供应商名称 */
    private String name;

    /** 供应商编码（deleted=0 范围内唯一） */
    private String code;

    /** OpenAI 兼容 base-url（须含 /v1，Spring AI 2.0 官方 SDK 约定） */
    private String baseUrl;

    /** 默认模型（对话未显式选择时兜底；模型池为空/全耗尽时的降级终点） */
    private String defaultModel;

    /**
     * 对话模型池（英文逗号分隔，如 {@code qwen3.8-flash,qwen3.7-flash}）。
     *
     * <p>用途：百炼「新人免费额度」按模型维度独立发放、且官方不会自动切换模型，
     * 配成池后由 {@code AiModelPoolRouter} 轮询分摊，某模型额度耗尽（403
     * {@code AllocationQuota.FreeTierOnly}）自动摘除并切下一个。
     *
     * <p>为空则退化为原有行为（只用 defaultModel）。
     * <b>池内模型应能力相近</b>——混进能力差距大的模型会让输出质量抖动。
     */
    private String modelPool;

    /** 向量化模型名（空则回退 spring.ai.openai.embedding.options.model） */
    private String embeddingModel;

    /** 重排模型名（如 qwen3-rerank，空则不启用重排，S65） */
    private String rerankModel;

    /** 排序（越小越靠前，默认供应商取启用中最靠前者） */
    private Integer sort;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

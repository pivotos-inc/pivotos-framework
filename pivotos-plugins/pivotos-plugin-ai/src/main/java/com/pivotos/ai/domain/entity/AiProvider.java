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

    /** 默认模型（对话未显式选择时兜底） */
    private String defaultModel;

    /** 排序（越小越靠前，默认供应商取启用中最靠前者） */
    private Integer sort;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

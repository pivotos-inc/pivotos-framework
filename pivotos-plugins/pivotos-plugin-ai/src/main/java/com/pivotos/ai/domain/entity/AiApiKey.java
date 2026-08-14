package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.crypto.FieldEncrypt;
import com.pivotos.starter.mybatis.crypto.FieldEncryptTypeHandler;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI API Key 实体（api_key AES 加密落库，autoResultMap 启用 TypeHandler 读路径）。
 * 多租户：继承 TenantBaseDO 参与行级隔离，tenant_id 随归属供应商（0=平台）。
 * 健康度：failCount 连续失败计数，达阈值自动停用 + 站内信告警。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ai_api_key", autoResultMap = true)
public class AiApiKey extends TenantBaseDO {

    /** 归属供应商ID */
    private Long providerId;

    /** 备注名（如"生产主 Key"） */
    private String label;

    /** Key 用途（chat=对话, embedding=向量化, all=通用） */
    private String purpose;

    /** API Key（透明加解密，列表返回前须脱敏） */
    @FieldEncrypt
    @TableField(typeHandler = FieldEncryptTypeHandler.class)
    private String apiKey;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 连续失败次数（成功清零，达 pivotos.ai.key-fail-threshold 自动停用） */
    private Integer failCount;
}

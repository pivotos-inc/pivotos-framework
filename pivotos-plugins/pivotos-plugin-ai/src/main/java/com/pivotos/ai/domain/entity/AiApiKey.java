package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.crypto.FieldEncrypt;
import com.pivotos.starter.mybatis.crypto.FieldEncryptTypeHandler;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI API Key 实体（api_key AES 加密落库，autoResultMap 启用 TypeHandler 读路径） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ai_api_key", autoResultMap = true)
public class AiApiKey extends BaseDO {

    /** 归属供应商ID */
    private Long providerId;

    /** 备注名（如"生产主 Key"） */
    private String label;

    /** API Key（透明加解密，列表返回前须脱敏） */
    @FieldEncrypt
    @TableField(typeHandler = FieldEncryptTypeHandler.class)
    private String apiKey;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 租户ID（多租户预留） */
    private Long tenantId;
}

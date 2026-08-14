package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * API Key 保存请求（id 为空新增、非空修改）。
 * 新增时 apiKey 明文必填（service 层判定）；修改时留空 = 不变更 Key 本体，
 * 已存 Key 不支持明文回看，只能重录覆盖。
 */
@Data
public class ApiKeySaveRequest {

    /** KeyID（修改时必传） */
    private Long id;

    /** 归属供应商ID */
    @NotNull(message = "供应商ID不能为空")
    private Long providerId;

    /** 备注名 */
    @Size(max = 64, message = "备注名最长 64 字符")
    private String label;

    /** Key 用途（chat=对话, embedding=向量化, all=通用；空默认 all） */
    private String purpose;

    /** API Key 明文（新增必填；修改留空表示不变更） */
    @Size(max = 256, message = "API Key 最长 256 字符")
    private String apiKey;

    /** 状态（0启用 1停用） */
    private Integer status;
}

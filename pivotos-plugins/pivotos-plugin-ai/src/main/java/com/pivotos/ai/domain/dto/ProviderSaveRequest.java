package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 供应商保存请求（id 为空新增、非空修改） */
@Data
public class ProviderSaveRequest {

    /** 供应商ID（修改时必传） */
    private Long id;

    /** 供应商名称 */
    @NotBlank(message = "供应商名称不能为空")
    @Size(max = 64, message = "供应商名称最长 64 字符")
    private String name;

    /** 供应商编码（小写字母/数字/中划线） */
    @NotBlank(message = "供应商编码不能为空")
    @Pattern(regexp = "^[a-z0-9-]{2,64}$", message = "编码仅限小写字母/数字/中划线（2-64 位）")
    private String code;

    /** OpenAI 兼容 base-url（须含 /v1） */
    @NotBlank(message = "base-url 不能为空")
    @Pattern(regexp = "^https?://.+", message = "base-url 须以 http(s):// 开头")
    @Size(max = 255, message = "base-url 最长 255 字符")
    private String baseUrl;

    /** 默认模型 */
    @Size(max = 64, message = "默认模型最长 64 字符")
    private String defaultModel;

    /** 向量化模型名（空则回退 spring.ai.openai.embedding.options.model） */
    @Size(max = 64, message = "向量化模型名最长 64 字符")
    private String embeddingModel;

    /** 排序 */
    private Integer sort;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 备注 */
    @Size(max = 255, message = "备注最长 255 字符")
    private String remark;
}

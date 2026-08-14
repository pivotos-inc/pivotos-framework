package com.pivotos.ai.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** API Key 视图对象（脱敏：只回尾 4 位，明文不可回看） */
@Data
@EqualsAndHashCode(callSuper = true)
public class ApiKeyVO extends BaseDTO {

    /** 归属供应商ID */
    private Long providerId;

    /** 备注名 */
    private String label;

    /** Key 用途（chat=对话, embedding=向量化, all=通用） */
    private String purpose;

    /** 脱敏 Key（如 sk-****abcd） */
    private String keyMasked;

    /** 状态（0启用 1停用） */
    private Integer status;

    /** 连续失败次数（健康度：成功清零，达阈值自动停用） */
    private Integer failCount;
}

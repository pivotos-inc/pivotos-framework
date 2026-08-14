package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 知识库修改入参。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KbBaseUpdateRequest extends KbBaseSaveRequest {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    @NotNull(message = "知识库 ID 不能为空")
    private Long id;
}

package com.pivotos.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 编排执行入参（A5-1 / S116） */
@Data
public class AiOrchestratorRunRequest {

    @NotBlank(message = "意图不能为空")
    private String intent;

    /**
     * 是否已获得用户对写操作的二次确认。
     *
     * <p>默认 false：写步骤一律停在确认闸前。前端必须在把计划里的写步骤逐条展示给用户、
     * 获得明确同意后再置 true——编排面不提供「跳过确认」的开关。
     */
    private Boolean confirmed = Boolean.FALSE;
}

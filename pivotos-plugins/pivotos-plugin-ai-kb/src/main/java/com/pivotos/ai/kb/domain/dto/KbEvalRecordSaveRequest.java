package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 检索评测跑分记录保存请求（S67）。
 *
 * <p>前端全量跑分完成后一次性提交逐题结果，聚合指标（Hit@K / MRR）由后端统一计算落库。
 */
@Data
public class KbEvalRecordSaveRequest {

    /** 关联知识库ID */
    @NotNull(message = "知识库ID不能为空")
    private Long kbId;

    /** 逐题跑分结果 */
    @NotEmpty(message = "跑分明细不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        /** 原评测问题ID */
        @Schema(description = "原评测问题ID")
        private Long questionId;

        /** 评测问题快照 */
        @NotBlank(message = "评测问题不能为空")
        @Size(max = 500, message = "评测问题最长 500 字")
        private String question;

        /** 预期命中关键词快照 */
        @NotBlank(message = "预期命中关键词不能为空")
        @Size(max = 200, message = "预期命中关键词最长 200 字")
        private String expectedKeyword;

        /** 基线首次命中排名（1-based，0=未命中） */
        @NotNull(message = "基线排名不能为空")
        private Integer baselineRank;

        /** 重排首次命中排名（0=未命中） */
        @NotNull(message = "重排排名不能为空")
        private Integer rerankRank;

        /** 是否改序 */
        @Schema(description = "是否改序")
        private Boolean orderChanged;
    }
}

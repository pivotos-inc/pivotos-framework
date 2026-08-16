package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/** 通知公告新增/修改请求 */
@Data
public class NoticeSaveRequest {

    /** 公告ID */
    @Schema(description = "公告ID")
    private Long id;

    /** 公告标题 */
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题不能超过 128 字")
    private String title;

    /** 类型（1通知 2公告） */
    @NotNull(message = "公告类型不能为空")
    private Integer noticeType;

    /** 富文本内容（HTML） */
    @Schema(description = "富文本内容（HTML）")
    private String content;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}

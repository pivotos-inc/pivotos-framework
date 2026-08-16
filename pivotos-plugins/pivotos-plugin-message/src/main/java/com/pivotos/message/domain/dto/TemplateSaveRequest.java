package com.pivotos.message.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/** 消息模板保存请求（新增/更新共用，更新时带 id） */
@Data
public class TemplateSaveRequest {

    /** 模板ID（更新必填） */
    @Schema(description = "模板ID（更新必填）")
    private Long id;

    /** 模板编码 */
    @NotBlank(message = "模板编码不能为空")
    @Size(max = 64, message = "模板编码长度不能超过 64")
    private String templateCode;

    /** 模板名称 */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 128, message = "模板名称长度不能超过 128")
    private String templateName;

    /** 标题模板（占位符 {var}） */
    @NotBlank(message = "标题模板不能为空")
    @Size(max = 255, message = "标题模板长度不能超过 255")
    private String titleTpl;

    /** 内容模板（占位符 {var}） */
    @NotBlank(message = "内容模板不能为空")
    private String contentTpl;

    /** 消息类型（1通知 2公告 3待办） */
    @Schema(description = "消息类型（1通知 2公告 3待办）")
    private Integer msgType;

    /** 默认渠道（inbox/sms/email） */
    @Schema(description = "默认渠道（inbox/sms/email）")
    private String channel;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}

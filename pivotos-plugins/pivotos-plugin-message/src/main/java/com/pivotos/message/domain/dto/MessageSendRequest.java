package com.pivotos.message.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 后台消息发送请求（templateCode 与 title/content 二选一） */
@Data
public class MessageSendRequest {

    /** 模板编码（可选） */
    @Schema(description = "模板编码（可选）")
    private String templateCode;

    /** 标题（不使用模板时必填） */
    @Size(max = 128, message = "标题长度不能超过 128")
    private String title;

    /** 内容（不使用模板时必填） */
    @Schema(description = "内容（不使用模板时必填）")
    private String content;

    /** 消息类型（1通知 2公告 3待办，默认 1） */
    @Schema(description = "消息类型（1通知 2公告 3待办，默认 1）")
    private Integer msgType;

    /** 渠道（inbox/sms/email，默认 inbox） */
    @Schema(description = "渠道（inbox/sms/email，默认 inbox）")
    private String channel;

    /** 业务类型（可选） */
    @Schema(description = "业务类型（可选）")
    private String bizType;

    /** 业务ID（可选） */
    @Schema(description = "业务ID（可选）")
    private String bizId;

    /** 接收人用户 ID 集合 */
    @NotEmpty(message = "接收人不能为空")
    private List<Long> receiverIds;
}

package com.pivotos.message.api.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 消息发送命令
 *
 * <p>templateCode 与 title/content 二选一：
 * 指定模板编码时按 msg_template 渲染（占位符 {@code {var}} 由 params 填充），
 * 否则直接使用 title/content。
 */
@Data
public class MessageSendCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板编码（可选，对应 msg_template.template_code） */
    @Schema(description = "模板编码（可选，对应 msg_template.template_code）")
    private String templateCode;

    /** 模板参数（可选，渲染占位符用） */
    private java.util.Map<String, Object> params;

    /** 标题（不使用模板时必填） */
    @Schema(description = "标题（不使用模板时必填）")
    private String title;

    /** 内容（不使用模板时必填） */
    @Schema(description = "内容（不使用模板时必填）")
    private String content;

    /** 消息类型（1 通知 2 公告 3 待办，默认 1） */
    @Schema(description = "消息类型（1 通知 2 公告 3 待办，默认 1）")
    private Integer msgType;

    /** 渠道（inbox 站内信 / sms 短信 / email 邮件，默认 inbox） */
    @Schema(description = "渠道（inbox 站内信 / sms 短信 / email 邮件，默认 inbox）")
    private String channel;

    /** 业务类型（可选） */
    @Schema(description = "业务类型（可选）")
    private String bizType;

    /** 业务 ID（可选） */
    @Schema(description = "业务 ID（可选）")
    private String bizId;

    /** 接收人用户 ID 集合（必填，非空） */
    @Schema(description = "接收人用户 ID 集合（必填，非空）")
    private List<Long> receiverIds;
}

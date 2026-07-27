package com.pivotos.message.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息发送日志实体（短信/邮件等外发渠道留痕） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_send_log")
public class MsgSendLog extends BaseDO {

    /** 关联消息ID */
    private Long messageId;

    /** 渠道（sms/email） */
    private String channel;

    /** 接收标识（手机号/邮箱/用户ID） */
    private String receiver;

    /** 标题快照 */
    private String title;

    /** 发送状态（0成功 1失败） */
    private Integer sendStatus;

    /** 失败原因 */
    private String errorMsg;

    /** 租户ID（多租户预留，S14 生效，当前由代码置 null） */
    private Long tenantId;
}

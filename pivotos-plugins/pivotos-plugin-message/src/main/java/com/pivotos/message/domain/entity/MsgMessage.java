package com.pivotos.message.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息主表实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_message")
public class MsgMessage extends BaseDO {

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 消息类型（1通知 2公告 3待办） */
    private Integer msgType;

    /** 业务类型（来源模块自定义） */
    private String bizType;

    /** 业务ID（跳转定位） */
    private String bizId;

    /** 租户ID（多租户预留，S14 生效，当前由代码置 null） */
    private Long tenantId;
}

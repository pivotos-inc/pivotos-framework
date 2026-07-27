package com.pivotos.message.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息模板实体（占位符 {var} 渲染） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("msg_template")
public class MsgTemplate extends BaseDO {

    /** 模板编码 */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 标题模板 */
    private String titleTpl;

    /** 内容模板 */
    private String contentTpl;

    /** 消息类型（1通知 2公告 3待办） */
    private Integer msgType;

    /** 默认渠道（inbox/sms/email） */
    private String channel;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 租户ID（多租户预留，S14 生效，当前由代码置 null） */
    private Long tenantId;
}

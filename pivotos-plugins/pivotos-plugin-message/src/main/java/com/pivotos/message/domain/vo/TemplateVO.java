package com.pivotos.message.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 消息模板视图对象 */
@Data
public class TemplateVO {

    /** 模板ID */
    private Long id;

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

    /** 默认渠道 */
    private String channel;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

package com.pivotos.message.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 后台消息视图对象（含接收人数统计） */
@Data
public class MessageManageVO {

    /** 消息ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 消息类型（1通知 2公告 3待办） */
    private Integer msgType;

    /** 业务类型 */
    private String bizType;

    /** 业务ID */
    private String bizId;

    /** 接收人数 */
    private Long receiverCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}

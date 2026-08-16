package com.pivotos.message.api.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 用户消息分页查询条件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MessagePageQuery extends PageQuery {

    /** 已读状态（0 未读 1 已读，null 全部） */
    @Schema(description = "已读状态（0 未读 1 已读，null 全部）")
    private Integer readStatus;

    /** 消息类型（1 通知 2 公告 3 待办，null 全部） */
    @Schema(description = "消息类型（1 通知 2 公告 3 待办，null 全部）")
    private Integer msgType;
}

package com.pivotos.message.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/** 后台消息分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MessageManageQuery extends PageQuery {

    /** 标题（模糊） */
    @Schema(description = "标题（模糊）")
    private String title;

    /** 消息类型（1通知 2公告 3待办） */
    @Schema(description = "消息类型（1通知 2公告 3待办）")
    private Integer msgType;

    /** 业务类型 */
    @Schema(description = "业务类型")
    private String bizType;
}

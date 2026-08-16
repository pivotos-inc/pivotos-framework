package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/** 通知公告分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeQuery extends PageQuery {

    /** 标题（模糊） */
    @Schema(description = "标题（模糊）")
    private String title;

    /** 类型（1通知 2公告） */
    @Schema(description = "类型（1通知 2公告）")
    private Integer noticeType;

    /** 状态（0草稿 1已发布 2已撤回） */
    @Schema(description = "状态（0草稿 1已发布 2已撤回）")
    private Integer status;
}

package com.pivotos.message.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 消息模板分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TemplateQuery extends PageQuery {

    /** 模板名称（模糊） */
    private String templateName;

    /** 模板编码（模糊） */
    private String templateCode;

    /** 状态（0正常 1停用） */
    private Integer status;
}

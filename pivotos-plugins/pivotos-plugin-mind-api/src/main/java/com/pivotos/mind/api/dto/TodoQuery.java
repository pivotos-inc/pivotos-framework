package com.pivotos.mind.api.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 待办列表查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TodoQuery extends PageQuery {

    /** 状态过滤：0 未完成 / 1 已完成 */
    private Integer status;
}

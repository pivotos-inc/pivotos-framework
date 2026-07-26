package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典数据分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataQuery extends PageQuery {

    /** 字典类型（精确） */
    private String dictType;

    /** 字典标签（模糊） */
    private String dictLabel;

    /** 状态 */
    private Integer status;
}

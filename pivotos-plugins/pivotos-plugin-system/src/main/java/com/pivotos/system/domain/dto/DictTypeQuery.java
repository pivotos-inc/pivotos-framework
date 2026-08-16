package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/** 字典类型分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeQuery extends PageQuery {

    /** 字典名称（模糊） */
    @Schema(description = "字典名称（模糊）")
    private String dictName;

    /** 字典类型（模糊） */
    @Schema(description = "字典类型（模糊）")
    private String dictType;

    /** 状态 */
    @Schema(description = "状态")
    private Integer status;
}

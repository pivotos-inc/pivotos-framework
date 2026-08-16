package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/** 参数配置分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigQuery extends PageQuery {

    /** 参数名称（模糊） */
    @Schema(description = "参数名称（模糊）")
    private String configName;

    /** 参数键名（模糊） */
    @Schema(description = "参数键名（模糊）")
    private String configKey;

    /** 内置标记（Y/N） */
    @Schema(description = "内置标记（Y/N）")
    private String configType;
}

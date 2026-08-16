package com.pivotos.docsync.api.dto;

import com.pivotos.common.core.page.PageQuery;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 文档同步配置分页查询
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocSyncConfigQuery extends PageQuery {

    /** 配置名称（模糊查询） */
    @Schema(description = "配置名称（模糊查询）")
    private String name;

    /** 平台类型 */
    @Schema(description = "平台类型")
    private DocSyncTypeEnum platformType;

    /** 是否启用 */
    @Schema(description = "是否启用")
    private Integer enabled;
}

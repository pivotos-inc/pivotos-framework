package com.pivotos.docsync.api.dto;

import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 平台信息 DTO（含配置字段元数据）
 *
 * <p>前端通过此对象获取平台类型、是否支持自动同步、特性描述，
 * 以及该平台所需的配置字段列表，用于动态渲染配置表单。
 */
@Data
public class PlatformInfoDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 平台类型枚举 */
    private DocSyncTypeEnum type;

    /** 平台显示名 */
    private String displayName;

    /** 是否支持自动同步 */
    private boolean autoSyncSupported;

    /** 平台特性描述 */
    private String description;

    /** 该平台所需的配置字段列表 */
    private List<DocSyncConfigFieldDTO> configFields;
}

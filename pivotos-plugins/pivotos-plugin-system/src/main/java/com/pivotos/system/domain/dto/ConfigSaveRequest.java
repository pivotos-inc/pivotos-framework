package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/** 参数配置新增/修改请求 */
@Data
public class ConfigSaveRequest {

    /** 参数ID */
    @Schema(description = "参数ID")
    private Long id;

    /** 参数名称 */
    @NotBlank(message = "参数名称不能为空")
    private String configName;

    /** 参数键名 */
    @NotBlank(message = "参数键名不能为空")
    private String configKey;

    /** 参数键值 */
    @NotBlank(message = "参数键值不能为空")
    private String configValue;

    /** 内置标记（Y系统内置 N自定义） */
    @Schema(description = "内置标记（Y系统内置 N自定义）")
    private String configType;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}

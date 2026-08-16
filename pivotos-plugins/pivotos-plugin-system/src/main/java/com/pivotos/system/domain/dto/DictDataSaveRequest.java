package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/** 字典数据新增/修改请求 */
@Data
public class DictDataSaveRequest {

    /** 字典数据ID */
    @Schema(description = "字典数据ID")
    private Long id;

    /** 字典类型 */
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    /** 字典标签 */
    @NotBlank(message = "字典标签不能为空")
    private String dictLabel;

    /** 字典键值 */
    @NotBlank(message = "字典键值不能为空")
    private String dictValue;

    /** 显示顺序 */
    @Schema(description = "显示顺序")
    private Integer sort;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}

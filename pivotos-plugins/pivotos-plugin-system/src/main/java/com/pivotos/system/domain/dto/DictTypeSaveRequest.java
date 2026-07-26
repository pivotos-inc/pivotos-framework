package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 字典类型新增/修改请求 */
@Data
public class DictTypeSaveRequest {

    /** 字典ID */
    private Long id;

    /** 字典名称 */
    @NotBlank(message = "字典名称不能为空")
    private String dictName;

    /** 字典类型 */
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

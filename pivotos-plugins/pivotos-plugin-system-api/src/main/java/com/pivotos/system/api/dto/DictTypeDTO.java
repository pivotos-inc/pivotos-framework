package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典类型传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeDTO extends BaseDTO {

    /** 字典名称 */
    private String dictName;

    /** 字典类型（如 sys_common_status） */
    private String dictType;

    /** 状态（0 正常 1 停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

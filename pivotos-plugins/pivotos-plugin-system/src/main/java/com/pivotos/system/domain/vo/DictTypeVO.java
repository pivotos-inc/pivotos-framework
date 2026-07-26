package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典类型视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeVO extends BaseDTO {

    /** 字典名称 */
    private String dictName;

    /** 字典类型 */
    private String dictType;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;
}

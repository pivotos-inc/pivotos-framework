package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典数据传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataDTO extends BaseDTO {

    /** 字典类型 */
    private String dictType;

    /** 字典标签 */
    private String dictLabel;

    /** 字典键值 */
    private String dictValue;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0 正常 1 停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

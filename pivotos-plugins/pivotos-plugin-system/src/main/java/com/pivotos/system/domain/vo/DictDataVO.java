package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典数据视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataVO extends BaseDTO {

    /** 字典类型 */
    private String dictType;

    /** 字典标签 */
    private String dictLabel;

    /** 字典键值 */
    private String dictValue;

    /** 显示顺序 */
    private Integer sort;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;
}

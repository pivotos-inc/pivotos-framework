package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 参数配置视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigVO extends BaseDTO {

    /** 参数名称 */
    private String configName;

    /** 参数键名 */
    private String configKey;

    /** 参数键值 */
    private String configValue;

    /** 内置标记 */
    private String configType;

    /** 备注 */
    private String remark;
}

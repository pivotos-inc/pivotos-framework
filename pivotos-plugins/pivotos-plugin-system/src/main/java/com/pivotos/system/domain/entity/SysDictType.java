package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典类型实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_type")
public class SysDictType extends BaseDO {

    /** 字典名称 */
    private String dictName;

    /** 字典类型（如 sys_common_status） */
    private String dictType;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}

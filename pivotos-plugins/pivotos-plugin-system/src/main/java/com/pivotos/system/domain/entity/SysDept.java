package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 部门实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseDO {

    /** 父部门ID（0为根） */
    private Long parentId;

    /** 部门名称 */
    private String deptName;

    /** 祖级列表，逗号分隔，如 0,1,2 */
    private String ancestors;

    /** 负责人用户ID */
    private Long leaderId;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0正常 1停用） */
    private Integer status;
}

package com.pivotos.generator.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代码生成器 - 业务表信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_gen_table")
public class GenTable extends BaseDO {

    @TableId
    private Long id;

    /** 表名称 */
    private String tableName;

    /** 表描述 */
    private String tableComment;

    /** 实体类名（首字母大写驼峰） */
    private String className;

    /** 父包名路径 */
    private String packageName;

    /** 模块名 */
    private String moduleName;

    /** 业务名 */
    private String businessName;

    /** 功能名称 */
    private String functionName;

    /** 生成功能作者 */
    private String functionAuthor;

    /** 生成方式（0 zip下载 1 写入工程） */
    private String genType;

    /** 生成路径（不填默认当前项目路径） */
    private String genPath;

    /** 备注 */
    private String remark;

    /** 模板类型（crud单表 tree树表 sub主子表） */
    private String tplCategory;

    /** 树编码字段（tpl_category=tree） */
    private String treeCode;

    /** 树父编码字段（tpl_category=tree） */
    private String treeParentCode;

    /** 树名称字段（tpl_category=tree） */
    private String treeName;

    /** 子表名（tpl_category=sub） */
    private String subTableName;

    /** 子表外键列名（tpl_category=sub） */
    private String subTableFkName;
}

package com.pivotos.generator.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代码生成器 - 业务表字段信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_gen_table_column")
public class GenTableColumn extends BaseDO {

    @TableId
    private Long id;

    /** 归属表 ID */
    private Long tableId;

    /** 列名称 */
    private String columnName;

    /** 列描述 */
    private String columnComment;

    /** 列类型 */
    private String columnType;

    /** JAVA 类型 */
    private String javaType;

    /** JAVA 字段名 */
    private String javaField;

    /** 是否主键（1是） */
    private Integer isPk;

    /** 是否自增（1是） */
    private Integer isIncrement;

    /** 是否必填（1是） */
    private Integer isRequired;

    /** 是否为插入字段（1是） */
    private Integer isInsert;

    /** 是否编辑字段（1是） */
    private Integer isEdit;

    /** 是否列表字段（1是） */
    private Integer isList;

    /** 是否查询字段（1是） */
    private Integer isQuery;

    /** 查询方式（EQ等于、NE不等于、GT大于、LT小于、LIKE模糊、BETWEEN范围） */
    private String queryType;

    /** 显示类型（input文本框、textarea文本域、select下拉、radio单选框、checkbox复选框、datetime日期时间） */
    private String htmlType;

    /** 字典类型 */
    private String dictType;

    /** 关联表名（fk 关联下拉） */
    private String fkTable;

    /** 关联值列 */
    private String fkValueColumn;

    /** 关联显示列 */
    private String fkLabelColumn;

    /** 排序 */
    private Integer sort;
}

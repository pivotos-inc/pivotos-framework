package com.pivotos.ai.coding.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Coding session: stores generated code preview before applying to project.
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Data
@TableName("sys_coding_session")
public class CodingSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** User's natural language description */
    private String description;

    /** Parsed module name */
    private String moduleName;

    /** Parsed table name */
    private String tableName;

    /** Parsed function name */
    private String functionName;

    /** Parsed business name */
    private String businessName;

    /** 0=parsing, 1=pending review, 2=applied, 3=failed */
    private Integer status;

    /** Generated files JSON: filePath -> content */
    private String generatedFilesJson;

    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.UPDATE)
    private String updateBy;

    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}

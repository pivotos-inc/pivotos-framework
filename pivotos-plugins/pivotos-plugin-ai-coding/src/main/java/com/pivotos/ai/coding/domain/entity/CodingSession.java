package com.pivotos.ai.coding.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI Coding session: stores generated code preview before applying to project.
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_coding_session")
public class CodingSession extends BaseDO {

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

    /** 1=单表CRUD, 2=Plugin骨架 */
    private Integer taskType;

    /** Generated files JSON: filePath -> content */
    private String generatedFilesJson;

    /** Task-type specific params JSON (e.g. plugin skeleton params) */
    private String extraJson;
}

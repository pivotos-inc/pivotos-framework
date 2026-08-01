package com.pivotos.generator.service;

import java.util.List;
import java.util.Map;

/**
 * Generator facade for AI Coding and other cross-module consumers.
 *
 * @author PivotOS
 * @since 2.2.0
 */
public interface IGeneratorFacade {

    /**
     * Import table definition from AI-parsed data.
     *
     * @param tableName    table name (e.g. biz_product)
     * @param moduleName   module name (e.g. system)
     * @param functionName function display name
     * @param businessName business name (e.g. product)
     * @param tableComment table comment
     * @param columns      column definitions from LLM parsing
     * @return generated table ID
     */
    Long importTable(String tableName, String moduleName, String functionName,
                     String businessName, String tableComment,
                     List<Map<String, Object>> columns);

    /**
     * Preview generated code for a table.
     *
     * @param tableId gen_table.id
     * @return map of file path to file content
     */
    Map<String, String> previewCode(Long tableId);

    /**
     * Write generated code to project directories.
     *
     * @param tableName table name to generate for
     */
    void generateToProject(String tableName);
}

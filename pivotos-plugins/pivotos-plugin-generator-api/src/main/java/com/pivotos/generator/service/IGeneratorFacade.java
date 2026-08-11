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

    /**
     * Preview a new plugin skeleton (S42 / 2.2-F12): dual-module poms, error-code
     * enum, facade placeholder, package-info, Flyway placeholder README, menu SQL template.
     * <p>
     * Paths are relative to the pivotos-framework root (pivotos-plugins/...);
     * no gen_table involvement. Params: pluginName, className, displayName,
     * tablePrefix, moduleDesc, errorCodeBase(int).
     *
     * @param params skeleton params
     * @return map of file path to file content
     */
    Map<String, String> previewPluginSkeleton(Map<String, Object> params);

    /**
     * Configure master-detail on an imported main table (S52 / 2.4-F5):
     * force-overwrite tplCategory=sub + subTableName + subTableFkName.
     * Force-overwrite (not skip-if-set) because importTable is idempotent and may
     * return a stale gen record whose sub config must be refreshed.
     *
     * @param mainTableName main table already imported via {@link #importTable}
     * @param subTableName  sub table already imported via {@link #importTable}
     * @param subFkName     fk column in sub table pointing to main id
     */
    void configureSubTable(String mainTableName, String subTableName, String subFkName);
}

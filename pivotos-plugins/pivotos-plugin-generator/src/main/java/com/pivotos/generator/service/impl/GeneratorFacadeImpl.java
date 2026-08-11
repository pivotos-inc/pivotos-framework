package com.pivotos.generator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.generator.api.enums.GeneratorErrorCode;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;
import com.pivotos.generator.mapper.GenTableColumnMapper;
import com.pivotos.generator.mapper.GenTableMapper;
import com.pivotos.generator.service.IGeneratorFacade;
import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Generator facade implementation for cross-module consumers (AI Coding, etc).
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Service
public class GeneratorFacadeImpl implements IGeneratorFacade {

    private static final Logger log = LoggerFactory.getLogger(GeneratorFacadeImpl.class);

    private final GenTableMapper genTableMapper;
    private final GenTableColumnMapper genTableColumnMapper;
    private final GeneratorServiceImpl generatorService;

    public GeneratorFacadeImpl(GenTableMapper genTableMapper,
                               GenTableColumnMapper genTableColumnMapper,
                               GeneratorServiceImpl generatorService) {
        this.genTableMapper = genTableMapper;
        this.genTableColumnMapper = genTableColumnMapper;
        this.generatorService = generatorService;
    }

    @Override
    @Transactional
    public Long importTable(String tableName, String moduleName, String functionName,
                             String businessName, String tableComment,
                             List<Map<String, Object>> columns) {
        // Check if table already exists in gen_table
        Long exists = genTableMapper.selectCount(
                new LambdaQueryWrapper<GenTable>().eq(GenTable::getTableName, tableName));
        if (exists != null && exists > 0) {
            log.warn("[Generator] Table already imported: {}", tableName);
            // Return existing table ID
            GenTable existing = genTableMapper.selectOne(
                    new LambdaQueryWrapper<GenTable>().eq(GenTable::getTableName, tableName));
            if (existing != null) return existing.getId();
        }

        // Create gen_table record
        GenTable genTable = new GenTable();
        genTable.setTableName(tableName);
        genTable.setTableComment(tableComment);
        genTable.setModuleName(moduleName);
        genTable.setFunctionName(functionName);
        genTable.setBusinessName(businessName);
        genTable.setClassName(toClassName(businessName));
        genTable.setPackageName(resolvePackageName(moduleName));
        genTable.setFunctionAuthor("AI Coding");
        genTableMapper.insert(genTable);

        // Create gen_table_column records
        int sort = 1;
        for (Map<String, Object> col : columns) {
            GenTableColumn column = new GenTableColumn();
            column.setTableId(genTable.getId());
            column.setColumnName((String) col.get("columnName"));
            column.setColumnComment((String) col.get("columnComment"));
            column.setColumnType((String) col.get("columnType"));
            column.setJavaType((String) col.get("javaType"));
            column.setJavaField((String) col.get("javaField"));
            column.setIsPk(Integer.valueOf(col.getOrDefault("isPk", 0).toString()));
            column.setIsRequired(Integer.valueOf(col.getOrDefault("isRequired", 0).toString()));
            column.setIsInsert(Integer.valueOf(col.getOrDefault("isInsert", 1).toString()));
            column.setIsEdit(Integer.valueOf(col.getOrDefault("isEdit", 1).toString()));
            column.setIsList(Integer.valueOf(col.getOrDefault("isList", 1).toString()));
            column.setIsQuery(Integer.valueOf(col.getOrDefault("isQuery", 0).toString()));
            column.setQueryType((String) col.getOrDefault("queryType", "EQ"));
            column.setHtmlType((String) col.getOrDefault("htmlType", "input"));
            column.setDictType((String) col.get("dictType"));
            // S50（2.4-F1）：fk 关联下拉配置透传（AI 多表意图预留，F5 启用）
            column.setFkTable((String) col.get("fkTable"));
            column.setFkValueColumn((String) col.get("fkValueColumn"));
            column.setFkLabelColumn((String) col.get("fkLabelColumn"));
            column.setSort(sort++);
            genTableColumnMapper.insert(column);
        }

        log.info("[Generator] AI table imported: table={}, columns={}, id={}",
                tableName, columns.size(), genTable.getId());
        return genTable.getId();
    }

    @Override
    public Map<String, String> previewCode(Long tableId) {
        return generatorService.previewCode(tableId);
    }

    @Override
    public void generateToProject(String tableName) {
        GenTable genTable = genTableMapper.selectOne(
                new LambdaQueryWrapper<GenTable>().eq(GenTable::getTableName, tableName));
        if (genTable == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        generatorService.generateToProject(genTable.getId());
    }

    @Override
    public Map<String, String> previewPluginSkeleton(Map<String, Object> params) {
        return generatorService.previewPluginSkeleton(params);
    }

    @Override
    public void configureSubTable(String mainTableName, String subTableName, String subFkName) {
        GenTable main = genTableMapper.selectOne(
                new LambdaQueryWrapper<GenTable>().eq(GenTable::getTableName, mainTableName));
        if (main == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        // 强制覆盖：importTable 幂等可能返回旧记录，主子配置必须刷新（S52 / 2.4-F5）
        main.setTplCategory("sub");
        main.setSubTableName(subTableName);
        main.setSubTableFkName(subFkName);
        genTableMapper.updateById(main);
        log.info("[Generator] 主子配置已写入: main={}, sub={}, fk={}", mainTableName, subTableName, subFkName);
    }

    private static String toClassName(String businessName) {
        if (businessName == null || businessName.isBlank()) return "Unknown";
        return businessName.substring(0, 1).toUpperCase() + businessName.substring(1);
    }

    /**
     * 包名解析：优先 com.pivotos.{moduleName}，但对应插件目录
     * （pivotos-plugins/pivotos-plugin-{moduleName}）不存在时回落 com.pivotos.system，
     * 避免产物落进不存在/不被扫描的模块成为死代码。
     */
    static String resolvePackageName(String moduleName) {
        String candidate = "com.pivotos." + moduleName;
        try {
            java.nio.file.Path pluginDir = resolveFrameworkRoot()
                    .resolve("pivotos-plugins/pivotos-plugin-" + moduleName);
            if (!java.nio.file.Files.isDirectory(pluginDir)) {
                log.warn("[Generator] 插件目录不存在，包名回落 com.pivotos.system: module={}", moduleName);
                return "com.pivotos.system";
            }
        } catch (Exception e) {
            log.warn("[Generator] 插件目录探测失败，按原包名继续: module={}", moduleName, e);
        }
        return candidate;
    }

    /** 从 user.dir 上溯定位 framework 根（含 pivotos-plugins/pom.xml 的目录） */
    private static java.nio.file.Path resolveFrameworkRoot() {
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (dir != null) {
            if (java.nio.file.Files.exists(dir.resolve("pivotos-plugins/pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return java.nio.file.Path.of(System.getProperty("user.dir"));
    }
}

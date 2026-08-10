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
        genTable.setPackageName("com.pivotos." + moduleName);
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
            column.setIsInsert(1);
            column.setIsEdit(1);
            column.setIsList(1);
            column.setIsQuery(Integer.valueOf(col.getOrDefault("isQuery", 0).toString()));
            column.setQueryType((String) col.getOrDefault("queryType", "EQ"));
            column.setHtmlType((String) col.getOrDefault("htmlType", "input"));
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

    private static String toClassName(String businessName) {
        if (businessName == null || businessName.isBlank()) return "Unknown";
        return businessName.substring(0, 1).toUpperCase() + businessName.substring(1);
    }
}

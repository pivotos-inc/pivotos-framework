package com.pivotos.generator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.generator.api.enums.GeneratorErrorCode;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;
import com.pivotos.generator.mapper.GenTableColumnMapper;
import com.pivotos.generator.mapper.GenTableMapper;
import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GeneratorServiceImpl core logic.
 * Uses Mockito mocks for database and template dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratorServiceImpl unit tests")
class GeneratorServiceImplTest {

    @Mock
    private GenTableMapper genTableMapper;

    @Mock
    private GenTableColumnMapper genTableColumnMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private GeneratorServiceImpl generatorService;

    private GenTable mockTable;
    private List<GenTableColumn> mockColumns;

    @BeforeEach
    void setUp() {
        // Initialize FreeMarker
        Configuration fmConfig = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        fmConfig.setClassLoaderForTemplateLoading(
                getClass().getClassLoader(), "templates/");
        fmConfig.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(generatorService, "freemarkerConfig", fmConfig);

        // Build mock table
        mockTable = new GenTable();
        mockTable.setId(1L);
        mockTable.setTableName("biz_product");
        mockTable.setTableComment("Product table");
        mockTable.setClassName("BizProduct");
        mockTable.setPackageName("com.pivotos.system");
        mockTable.setModuleName("system");
        mockTable.setBusinessName("product");
        mockTable.setFunctionName("Product Management");
        mockTable.setFunctionAuthor("PivotOS");
        mockTable.setGenType("0");

        // Build mock columns
        mockColumns = List.of(
                buildColumn(1L, 1L, "id", "id", "bigint", "Long", 1, 0, 0, 0, 0, 0, "id", "EQ", "input", 1),
                buildColumn(2L, 1L, "product_name", "productName", "varchar(100)", "String", 0, 1, 1, 1, 1, 1, "product_name", "LIKE", "input", 2),
                buildColumn(3L, 1L, "price", "price", "decimal(10,2)", "BigDecimal", 0, 1, 1, 1, 1, 0, "price", "EQ", "input", 3),
                buildColumn(4L, 1L, "create_time", "createTime", "datetime", "LocalDateTime", 0, 0, 0, 0, 0, 0, "create_time", "BETWEEN", "datetime", 4)
        );
    }

    // ==================== selectGenTableById ====================

    @Test
    @DisplayName("selectGenTableById - found returns entity")
    void testSelectGenTableByIdFound() {
        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        GenTable result = generatorService.selectGenTableById(1L);
        assertNotNull(result);
        assertEquals("BizProduct", result.getClassName());
    }

    @Test
    @DisplayName("selectGenTableById - not found throws exception")
    void testSelectGenTableByIdNotFound() {
        when(genTableMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> generatorService.selectGenTableById(999L));
        assertEquals(GeneratorErrorCode.GEN_TABLE_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== selectGenTableColumnListByTableId ====================

    @Test
    @DisplayName("selectGenTableColumnListByTableId - returns ordered columns")
    void testSelectGenTableColumnListByTableId() {
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns);
        List<GenTableColumn> result = generatorService.selectGenTableColumnListByTableId(1L);
        assertEquals(4, result.size());
        assertEquals("id", result.get(0).getColumnName());
        assertEquals("create_time", result.get(3).getColumnName());
    }

    // ==================== deleteGenTable ====================

    @Test
    @DisplayName("deleteGenTable - cascades column deletion")
    void testDeleteGenTable() {
        when(genTableColumnMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(genTableMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> generatorService.deleteGenTable(List.of(1L)));

        verify(genTableColumnMapper).delete(any(LambdaQueryWrapper.class));
        verify(genTableMapper).deleteById(1L);
    }

    // ==================== previewCode error cases ====================

    @Test
    @DisplayName("previewCode - table not found throws error")
    void testPreviewCodeTableNotFound() {
        when(genTableMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> generatorService.previewCode(999L));
        assertEquals(GeneratorErrorCode.GEN_TABLE_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== generateToProject ====================

    @Test
    @DisplayName("generateToProject - null result from preview triggers error")
    void testGenerateToProjectNotFound() {
        when(genTableMapper.selectById(999L)).thenReturn(null);
        assertThrows(NullPointerException.class,
                () -> generatorService.generateToProject(999L));
    }

    // ==================== downloadCode ====================

    @Test
    @DisplayName("downloadCode - table not found throws error")
    void testDownloadCodeTableNotFound() {
        when(genTableMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> generatorService.downloadCode(999L));
        assertEquals(GeneratorErrorCode.GEN_TABLE_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== Helper ====================

    private GenTableColumn buildColumn(Long id, Long tableId, String columnName,
                                        String javaField, String columnType, String javaType,
                                        int isPk, int isRequired, int isInsert, int isEdit,
                                        int isList, int isQuery, String columnComment,
                                        String queryType, String htmlType, int sort) {
        GenTableColumn col = new GenTableColumn();
        col.setId(id);
        col.setTableId(tableId);
        col.setColumnName(columnName);
        col.setJavaField(javaField);
        col.setColumnType(columnType);
        col.setJavaType(javaType);
        col.setIsPk(isPk);
        col.setIsRequired(isRequired);
        col.setIsInsert(isInsert);
        col.setIsEdit(isEdit);
        col.setIsList(isList);
        col.setIsQuery(isQuery);
        col.setColumnComment(columnComment);
        col.setQueryType(queryType);
        col.setHtmlType(htmlType);
        col.setSort(sort);
        return col;
    }
}

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

    // ==================== previewCode 渲染（S43：dto/vo 模板 + 菜单 SQL） ====================

    @Test
    @DisplayName("previewCode - 产物含 dto/vo 四件套且可编译形态正确")
    void testPreviewCodeContainsDtoVo() {
        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns);

        Map<String, String> files = generatorService.previewCode(1L);

        String javaBase = "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";
        String createReq = files.get(javaBase + "/domain/dto/BizProductCreateRequest.java");
        String updateReq = files.get(javaBase + "/domain/dto/BizProductUpdateRequest.java");
        String queryReq = files.get(javaBase + "/domain/dto/BizProductQueryRequest.java");
        String vo = files.get(javaBase + "/domain/vo/BizProductVO.java");
        assertNotNull(createReq);
        assertNotNull(updateReq);
        assertNotNull(queryReq);
        assertNotNull(vo);

        // CreateRequest：必填校验 + 无 id/审计字段
        assertTrue(createReq.contains("class BizProductCreateRequest"));
        assertTrue(createReq.contains("@NotBlank(message = \"product_name不能为空\")"));
        assertFalse(createReq.contains("createTime"));
        // UpdateRequest：带 @NotNull id
        assertTrue(updateReq.contains("@NotNull(message = \"id 不能为空\")"));
        assertTrue(updateReq.contains("private Long id;"));
        // QueryRequest：继承 PageQuery + LIKE 字段
        assertTrue(queryReq.contains("extends PageQuery"));
        assertTrue(queryReq.contains("private String productName;"));
        // Controller/ServiceImpl：PageResult 形态（对齐手写分层 + useTablePage 的 list/total 约定）
        String controller = files.get(javaBase + "/controller/BizProductController.java");
        String serviceImpl = files.get(javaBase + "/service/impl/BizProductServiceImpl.java");
        assertNotNull(controller);
        assertNotNull(serviceImpl);
        assertTrue(controller.contains("R<PageResult<BizProductVO>> selectPage"));
        assertTrue(serviceImpl.contains("new PageResult<>(voList, page.getTotal()"));
        // PC api：Query 继承 PageQuery（useTablePage 泛型约束，S43 typecheck 实测）
        String pcApi = files.get("pivotos-ui/apps/admin/src/api/system/product.ts");
        assertNotNull(pcApi);
        assertTrue(pcApi.contains("export interface BizProductQuery extends PageQuery"));
        // VO：继承 BaseDTO、剔除审计字段、含 BigDecimal import
        assertTrue(vo.contains("class BizProductVO extends BaseDTO"));
        assertTrue(vo.contains("private BigDecimal price;"));
        assertTrue(vo.contains("import java.math.BigDecimal;"));
        assertFalse(vo.contains("private LocalDateTime createTime;"));
    }

    @Test
    @DisplayName("previewCode - flyway 菜单段重写为 1100 + C/F 按钮 + 动态 id")
    void testPreviewCodeFlywayMenu() {
        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns);

        Map<String, String> files = generatorService.previewCode(1L);
        String sqlKey = files.keySet().stream().filter(k -> k.startsWith("sql/")).findFirst().orElseThrow();
        String sql = files.get(sqlKey);

        assertTrue(sql.contains("SET @gen_menu_id :="));
        assertTrue(sql.contains("@gen_menu_id, 1100,"));
        assertTrue(sql.contains("'system:product:list'"));
        assertTrue(sql.contains("'system:product:add'"));
        assertTrue(sql.contains("'system:product:edit'"));
        assertTrue(sql.contains("'system:product:remove'"));
        assertTrue(sql.contains("'system:product:query'"));
        // 本仓约定 0=正常/可见，禁止 UNIX_TIMESTAMP 随机 id
        assertFalse(sql.contains("UNIX_TIMESTAMP"));
        // DDL 覆盖全部业务字段（不随 isInsert 变化丢列）
        assertTrue(sql.contains("`price` decimal(10,2)"));
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

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
    @DisplayName("previewCode - S47 app 侧并行端点组 + uni API /app 前缀")
    void testPreviewCodeAppSideEndpoints() {
        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns);

        Map<String, String> files = generatorService.previewCode(1L);

        String javaBase = "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";
        // app 侧并行控制器：/app 前缀 + StpMobileUtil 登录校验（app-user/wx-mini-user 任一放行）
        String appController = files.get(javaBase + "/controller/BizProductAppController.java");
        assertNotNull(appController);
        assertTrue(appController.contains("class BizProductAppController"));
        assertTrue(appController.contains("@RequestMapping(\"/app/system/product\")"));
        assertTrue(appController.contains("StpMobileUtil.checkLogin();"));
        assertFalse(appController.contains("StpSysUtil"));
        // sys 端点组不动：权限串仍钉 sys 体系
        String controller = files.get(javaBase + "/controller/BizProductController.java");
        assertTrue(controller.contains("type = StpSysUtil.TYPE"));
        // uni API：统一 /app 前缀
        String uniApi = files.get("pivotos-app/src/api/system/product.ts");
        assertNotNull(uniApi);
        assertTrue(uniApi.contains("'/app/system/product/page'"));
        assertTrue(uniApi.contains("'/app/system/product/' + id"));
        assertFalse(uniApi.contains("'/system/product/page'"));
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

    // ==================== S50：fk 关联下拉（2.4-F2） ====================

    @Test
    @DisplayName("previewCode - fk 列产出 VO 翻译 + 选项端点 + 双端 select/picker")
    void testPreviewCodeFkDropdown() {
        GenTableColumn fkCol = buildColumn(5L, 1L, "dept_id", "deptId", "bigint", "Long",
                0, 1, 1, 1, 1, 1, "dept_id", "EQ", "input", 5);
        fkCol.setFkTable("sys_dept");
        fkCol.setFkValueColumn("id");
        fkCol.setFkLabelColumn("dept_name");
        List<GenTableColumn> cols = new java.util.ArrayList<>(mockColumns);
        cols.add(fkCol);

        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(cols);
        // deleted 列探测：sys_dept 有 deleted → 选项/翻译 SQL 带过滤
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Long.class), eq("sys_dept")))
                .thenReturn(1L);

        Map<String, String> files = generatorService.previewCode(1L);
        String javaBase = "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";

        // VO：label 字段
        String vo = files.get(javaBase + "/domain/vo/BizProductVO.java");
        assertTrue(vo.contains("private String deptIdLabel;"));
        // ServiceImpl：FK_CONFIG + 批量回填 + deleted 过滤 + 选项查询
        String serviceImpl = files.get(javaBase + "/service/impl/BizProductServiceImpl.java");
        assertTrue(serviceImpl.contains("FK_CONFIG"));
        assertTrue(serviceImpl.contains("Map.entry(\"deptId\", new String[]{\"sys_dept\", \"id\", \"dept_name\""));
        assertTrue(serviceImpl.contains("fillFkLabels(voList)"));
        assertTrue(serviceImpl.contains("fillDeptIdLabel(vos)"));
        assertTrue(serviceImpl.contains(" AND `deleted` = 0"));
        assertTrue(serviceImpl.contains("selectFkOptions(String field)"));
        // Controller：sys/app 双侧选项端点
        String controller = files.get(javaBase + "/controller/BizProductController.java");
        assertTrue(controller.contains("/fk-options/{field}"));
        assertTrue(controller.contains("system:product:query"));
        String appController = files.get(javaBase + "/controller/BizProductAppController.java");
        assertTrue(appController.contains("/fk-options/{field}"));
        // PC：表格 label 列 + 表单/搜索 select + api 选项函数
        String pcPage = files.get("pivotos-ui/apps/admin/src/views/system/product/index.vue");
        assertTrue(pcPage.contains("prop: 'deptIdLabel'"));
        assertTrue(pcPage.contains("component: 'select', options: fkOptions.deptId"));
        String pcApi = files.get("pivotos-ui/apps/admin/src/api/system/product.ts");
        assertTrue(pcApi.contains("getBizProductFkOptions"));
        assertTrue(pcApi.contains("deptIdLabel?: string;"));
        // uni：picker + /app 前缀选项 + 列表/详情 label 展示
        String uniForm = files.get("pivotos-app/src/pages-gen/system/product/form.vue");
        assertTrue(uniForm.contains("<wd-picker"));
        assertTrue(uniForm.contains(":columns=\"deptIdOptions\""));
        String uniList = files.get("pivotos-app/src/pages-gen/system/product/list.vue");
        assertTrue(uniList.contains("item.deptIdLabel || item.deptId"));
        String uniApi = files.get("pivotos-app/src/api/system/product.ts");
        assertTrue(uniApi.contains("'/app/system/product/fk-options/' + field"));
    }

    @Test
    @DisplayName("previewCode - fk 配置不完整（缺值列/显示列）按无 fk 处理")
    void testPreviewCodeFkIncompleteIgnored() {
        GenTableColumn fkCol = buildColumn(5L, 1L, "dept_id", "deptId", "bigint", "Long",
                0, 1, 1, 1, 1, 1, "dept_id", "EQ", "input", 5);
        fkCol.setFkTable("sys_dept"); // 缺 value/label 列
        List<GenTableColumn> cols = new java.util.ArrayList<>(mockColumns);
        cols.add(fkCol);
        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(cols);

        Map<String, String> files = generatorService.previewCode(1L);
        String javaBase = "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";
        String serviceImpl = files.get(javaBase + "/service/impl/BizProductServiceImpl.java");
        assertFalse(serviceImpl.contains("FK_CONFIG"));
    }

    // ==================== S51：主子表模板族（2.4-F3） ====================

    private GenTable buildSubTable() {
        GenTable sub = new GenTable();
        sub.setId(2L);
        sub.setTableName("biz_order_item");
        sub.setTableComment("Order item table");
        sub.setClassName("BizOrderItem");
        sub.setPackageName("com.pivotos.system");
        sub.setModuleName("system");
        sub.setBusinessName("orderitem");
        sub.setFunctionName("Order Item");
        sub.setFunctionAuthor("PivotOS");
        return sub;
    }

    private List<GenTableColumn> buildSubColumns() {
        return List.of(
                buildColumn(11L, 2L, "id", "id", "bigint", "Long", 1, 0, 0, 0, 0, 0, "id", "EQ", "input", 1),
                buildColumn(12L, 2L, "order_id", "orderId", "bigint", "Long", 0, 0, 1, 1, 0, 0, "order_id", "EQ", "input", 2),
                buildColumn(13L, 2L, "product_name", "productName", "varchar(100)", "String", 0, 1, 1, 1, 1, 0, "product_name", "EQ", "input", 3),
                buildColumn(14L, 2L, "quantity", "quantity", "int", "Integer", 0, 0, 1, 1, 1, 0, "quantity", "EQ", "input", 4),
                buildColumn(15L, 2L, "price", "price", "decimal(10,2)", "BigDecimal", 0, 0, 1, 1, 1, 0, "price", "EQ", "input", 5),
                buildColumn(16L, 2L, "create_time", "createTime", "datetime", "LocalDateTime", 0, 0, 0, 0, 0, 0, "create_time", "BETWEEN", "datetime", 6)
        );
    }

    @Test
    @DisplayName("previewCode - 主子表产出子四件套 + 事务主子 ServiceImpl + 双端明细编辑")
    void testPreviewCodeSubTable() {
        mockTable.setTplCategory("sub");
        mockTable.setSubTableName("biz_order_item");
        mockTable.setSubTableFkName("order_id");

        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GenTable>>any()))
                .thenReturn(buildSubTable());
        // 第 1 次取主表字段、第 2 次取子表字段（buildModel 顺序）
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns, buildSubColumns());

        Map<String, String> files = generatorService.previewCode(1L);
        String javaBase = "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";

        // 子表四件套：子实体 / 子 Mapper / 子 VO / 子项 DTO
        String subEntity = files.get(javaBase + "/domain/entity/BizOrderItem.java");
        String subMapper = files.get(javaBase + "/mapper/BizOrderItemMapper.java");
        String subVo = files.get(javaBase + "/domain/vo/BizOrderItemVO.java");
        String subItemDto = files.get(javaBase + "/domain/dto/BizOrderItemItemRequest.java");
        assertNotNull(subEntity);
        assertNotNull(subMapper);
        assertNotNull(subVo);
        assertNotNull(subItemDto);
        assertTrue(subEntity.contains("class BizOrderItem"));
        // 子 VO 剔除 fk 与审计字段
        assertTrue(subVo.contains("private String productName;"));
        assertFalse(subVo.contains("orderId"));
        assertFalse(subVo.contains("createTime"));

        // 主 DTO / VO 携带 items
        String createReq = files.get(javaBase + "/domain/dto/BizProductCreateRequest.java");
        String vo = files.get(javaBase + "/domain/vo/BizProductVO.java");
        assertTrue(createReq.contains("List<BizOrderItemItemRequest> items"));
        assertTrue(createReq.contains("@Valid"));
        assertTrue(vo.contains("List<BizOrderItemVO> items"));

        // ServiceImpl：事务主子——批量插入 + fk 回写 + 全量替换语义
        String serviceImpl = files.get(javaBase + "/service/impl/BizProductServiceImpl.java");
        assertTrue(serviceImpl.contains("Db.saveBatch"));
        assertTrue(serviceImpl.contains("sub.setOrderId(mainId)"));
        assertTrue(serviceImpl.contains("BizOrderItemMapper"));

        // flyway SQL：双表 DDL
        String sqlKey = files.keySet().stream().filter(k -> k.startsWith("sql/")).findFirst().orElseThrow();
        String sql = files.get(sqlKey);
        assertTrue(sql.contains("biz_product"));
        assertTrue(sql.contains("biz_order_item"));

        // PC：主从页内嵌明细编辑
        String pcPage = files.get("pivotos-ui/apps/admin/src/views/system/product/index.vue");
        assertTrue(pcPage.contains("添加行"));
        assertTrue(pcPage.contains("subRows"));
        assertTrue(pcPage.contains("items: subRows.value"));
        // PC API：子项类型
        String pcApi = files.get("pivotos-ui/apps/admin/src/api/system/product.ts");
        assertTrue(pcApi.contains("BizOrderItemItem"));
        assertTrue(pcApi.contains("items?: BizOrderItemItem[];"));

        // uni：明细卡片 + 提交携带 items
        String uniForm = files.get("pivotos-app/src/pages-gen/system/product/form.vue");
        assertTrue(uniForm.contains("添加明细"));
        assertTrue(uniForm.contains("form.items = items.value"));
    }

    @Test
    @DisplayName("previewCode - 子表未导入生成器报 GEN_SUB_TABLE_NOT_FOUND")
    void testPreviewCodeSubTableNotImported() {
        mockTable.setTplCategory("sub");
        mockTable.setSubTableName("biz_order_item");
        mockTable.setSubTableFkName("order_id");

        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns);
        when(genTableMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GenTable>>any()))
                .thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> generatorService.previewCode(1L));
        assertEquals(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("previewCode - 子表外键列不存在报 GEN_SUB_TABLE_NOT_FOUND")
    void testPreviewCodeSubFkColumnMissing() {
        mockTable.setTplCategory("sub");
        mockTable.setSubTableName("biz_order_item");
        mockTable.setSubTableFkName("not_exist_fk");

        when(genTableMapper.selectById(1L)).thenReturn(mockTable);
        when(genTableMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GenTable>>any()))
                .thenReturn(buildSubTable());
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mockColumns, buildSubColumns());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> generatorService.previewCode(1L));
        assertEquals(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND.getCode(), ex.getCode());
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

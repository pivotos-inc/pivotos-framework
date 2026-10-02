package com.pivotos.generator.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.common.api.dto.BaseDTO;
import com.pivotos.common.core.page.PageQuery;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;
import com.pivotos.generator.mapper.GenTableColumnMapper;
import com.pivotos.generator.mapper.GenTableMapper;
import com.pivotos.starter.auth.account.StpMobileUtil;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.mybatis.domain.BaseDO;
import freemarker.template.Configuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L7 清偿：生成器产物「真编译」契约（不是静态合法性校验）。
 *
 * <p>背景：历史欠账 L7 记录「迁移产物自测当前为合法性静态校验，真编译/真 typecheck 校验未做」，
 * 且 S37 曾实测「生成器后端模板引用未生成的 DTO/VO → 产物编译不过」。既有
 * {@code GeneratorServiceImplTest} 只做字符串包含断言，产物能否通过 javac 从未被钉死。
 *
 * <p>本契约的做法：
 * <ol>
 *   <li>用 Mockito 造表/字段元数据，走真实的 {@link GeneratorServiceImpl#previewCode}；</li>
 *   <li>把产物里的 {@code *.java} 按包路径落盘到临时目录；</li>
 *   <li>用 JDK 自带的 {@link JavaCompiler} 实编译（classpath 由锚点类的 {@code CodeSource} 反推，
 *       不依赖 {@code java.class.path}——surefire 的 manifest-only jar 会让后者只剩一个引导包）；</li>
 *   <li>断言零 error 诊断，且编译出的 class 数量与源文件数量一致（防「零文件假绿」）。</li>
 * </ol>
 *
 * <p>锚点类清单即产物所依赖的第三方坐标白名单：一旦模板引用了新的依赖，必须在这里显式登记，
 * 否则编译期就会缺类——这是有意为之的「依赖引入需过会」闸门。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("L7 生成器产物真编译契约")
class GeneratedCodeCompileTest {

    /** 产物依赖的锚点类（用于反推真实 jar 路径） */
    private static final List<Class<?>> CLASSPATH_ANCHORS = List.of(
            // 底座
            BaseDO.class, BaseDTO.class, PageQuery.class, PageResult.class, R.class,
            com.pivotos.common.core.exception.ServiceException.class,
            // MyBatis-Plus / MyBatis
            com.baomidou.mybatisplus.core.mapper.BaseMapper.class,
            com.baomidou.mybatisplus.core.toolkit.Wrappers.class,
            com.baomidou.mybatisplus.extension.plugins.pagination.Page.class,
            com.baomidou.mybatisplus.annotation.TableName.class,
            org.apache.ibatis.annotations.Mapper.class,
            // 鉴权
            StpSysUtil.class, StpMobileUtil.class,
            cn.dev33.satoken.annotation.SaCheckPermission.class,
            // Spring
            org.springframework.web.bind.annotation.RestController.class,
            org.springframework.stereotype.Service.class,
            org.springframework.transaction.annotation.Transactional.class,
            org.springframework.jdbc.core.JdbcTemplate.class,
            org.springframework.dao.DataAccessException.class,
            org.springframework.core.NestedRuntimeException.class,
            jakarta.annotation.Resource.class,
            org.springframework.beans.factory.InitializingBean.class,
            // 校验 / 工具
            Valid.class, NotBlank.class,
            cn.hutool.core.bean.BeanUtil.class,
            org.slf4j.Logger.class,
            // Lombok（同时作为注解处理器路径）
            Data.class
    );

    @Mock
    private GenTableMapper genTableMapper;

    @Mock
    private GenTableColumnMapper genTableColumnMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private GeneratorServiceImpl generatorService;

    @TempDir
    private Path tempDir;

    private static final String JAVA_BASE =
            "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system";

    @BeforeEach
    void setUp() {
        Configuration fmConfig = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        fmConfig.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates/");
        fmConfig.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(generatorService, "freemarkerConfig", fmConfig);
    }

    // ==================== 契约主体 ====================

    @Test
    @DisplayName("crud 模板族：后端产物全部通过 javac 实编译")
    void crudArtifactsCompile() throws IOException {
        GenTable table = table("crud");
        List<GenTableColumn> columns = mainColumns();
        stub(table, columns);

        CompiledArtifact artifact = compileAll("crud", generatorService.previewCode(1L));

        assertTrue(artifact.javaCount >= 9, "crud 至少应产出 9 个 Java 文件，实际 " + artifact.javaCount);
        assertNoErrors(artifact);
        assertEquals(artifact.javaCount, artifact.classCount, "编译产物 class 数应与源文件数一致");
    }

    @Test
    @DisplayName("fk 关联模板族：带 FK_CONFIG / 批量回填的产物通过 javac 实编译")
    void fkArtifactsCompile() throws IOException {
        GenTable table = table("crud");
        List<GenTableColumn> columns = new ArrayList<>(mainColumns());
        GenTableColumn fk = column(5L, "dept_id", "deptId", "bigint", "Long", 0, 1, 1, 1, 1, 1, "dept_id");
        fk.setFkTable("sys_dept");
        fk.setFkValueColumn("id");
        fk.setFkLabelColumn("dept_name");
        columns.add(fk);
        stub(table, columns);
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("information_schema.columns"),
                eq(Long.class), eq("sys_dept"))).thenReturn(1L);

        CompiledArtifact artifact = compileAll("fk", generatorService.previewCode(1L));

        String serviceImpl = artifact.sources.get(JAVA_BASE + "/service/impl/BizProductServiceImpl.java");
        assertTrue(serviceImpl != null && serviceImpl.contains("FK_CONFIG"), "fk 场景应产出 FK_CONFIG");
        assertNoErrors(artifact);
    }

    @Test
    @DisplayName("tree 模板族：树组装 ServiceImpl + 子节点 VO 通过 javac 实编译")
    void treeArtifactsCompile() throws IOException {
        GenTable table = table("tree");
        table.setTreeCode("id");
        table.setTreeParentCode("parent_id");
        table.setTreeName("product_name");

        List<GenTableColumn> columns = new ArrayList<>(mainColumns());
        columns.add(column(6L, "parent_id", "parentId", "bigint", "Long", 0, 0, 1, 1, 1, 0, "parent_id"));
        stub(table, columns);

        CompiledArtifact artifact = compileAll("tree", generatorService.previewCode(1L));

        String vo = artifact.sources.get(JAVA_BASE + "/domain/vo/BizProductVO.java");
        assertTrue(vo != null && vo.contains("List<BizProductVO> children"), "树 VO 应含 children");
        assertNoErrors(artifact);
    }

    @Test
    @DisplayName("sub 主子模板族：主四件套 + 子四件套 + 子项 DTO 通过 javac 实编译")
    void subArtifactsCompile() throws IOException {
        GenTable table = table("sub");
        table.setSubTableName("biz_order_item");
        table.setSubTableFkName("order_id");

        GenTable subTable = new GenTable();
        subTable.setId(2L);
        subTable.setTableName("biz_order_item");
        subTable.setTableComment("Order item table");
        subTable.setClassName("BizOrderItem");
        subTable.setPackageName("com.pivotos.system");
        subTable.setModuleName("system");
        subTable.setBusinessName("orderitem");
        subTable.setFunctionName("Order Item");
        subTable.setFunctionAuthor("PivotOS");

        List<GenTableColumn> subColumns = List.of(
                column(11L, "id", "id", "bigint", "Long", 1, 0, 0, 0, 0, 0, "id"),
                column(12L, "order_id", "orderId", "bigint", "Long", 0, 0, 1, 1, 0, 0, "order_id"),
                column(13L, "product_name", "productName", "varchar(100)", "String", 0, 1, 1, 1, 1, 0, "product_name"),
                column(14L, "quantity", "quantity", "int", "Integer", 0, 0, 1, 1, 1, 0, "quantity"),
                column(15L, "price", "price", "decimal(10,2)", "BigDecimal", 0, 0, 1, 1, 1, 0, "price")
        );

        when(genTableMapper.selectById(1L)).thenReturn(table);
        when(genTableMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GenTable>>any())).thenReturn(subTable);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(mainColumns(), subColumns);

        CompiledArtifact artifact = compileAll("sub", generatorService.previewCode(1L));

        // 主子表多产 4 个文件：子实体 / 子 Mapper / 子 VO / 子项 DTO
        assertTrue(artifact.javaCount >= 13, "sub 至少应产出 13 个 Java 文件，实际 " + artifact.javaCount);
        assertTrue(artifact.sources.containsKey(JAVA_BASE + "/domain/dto/BizOrderItemItemRequest.java"));
        assertNoErrors(artifact);
    }

    /**
     * 门禁自证（防「假绿」）：本契约的核心是 javac 实编译，若编译引擎悄悄退化成「什么都不编译」，
     * 上面四个用例会一起变绿却毫无意义。这里塞一个必然编译失败的文件，断言引擎能报出 error。
     */
    @Test
    @DisplayName("门禁自证：注入非法源码时编译引擎必须报出 ERROR")
    void compileEngineDetectsBrokenSource() throws IOException {
        Path srcRoot = Files.createDirectories(tempDir.resolve("negative").resolve("src"));
        Path outRoot = Files.createDirectories(tempDir.resolve("negative").resolve("out"));
        Path broken = srcRoot.resolve("Broken.java");
        Files.writeString(broken, """
                public class Broken {
                    void x() { return com.pivotos.NoSuchType.NOW(); }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(List.of(broken.toFile()));
            ok = compiler.getTask(null, fm, diagnostics,
                    List.of("-classpath", classpath(), "-d", outRoot.toString()), null, units).call();
        }
        assertFalse(ok, "非法源码应编译失败");
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind().name().equals("ERROR")),
                "编译引擎应报出 ERROR 诊断");
    }

    // ==================== 编译引擎 ====================

    private void assertNoErrors(CompiledArtifact artifact) {
        List<String> errors = artifact.diagnostics.stream()
                .filter(d -> d.startsWith("ERROR"))
                .toList();
        assertTrue(errors.isEmpty(),
                "生成器产物应零编译错误，实际 " + errors.size() + " 处：\n" + String.join("\n", errors));
    }

    private void stub(GenTable table, List<GenTableColumn> columns) {
        when(genTableMapper.selectById(1L)).thenReturn(table);
        when(genTableColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(columns);
    }

    private CompiledArtifact compileAll(String caseName, Map<String, String> files) throws IOException {
        Map<String, String> javaFiles = files.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".java"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new));
        assertTrue(!javaFiles.isEmpty(), caseName + " 场景应产出 Java 文件");

        Path srcRoot = Files.createDirectories(tempDir.resolve(caseName).resolve("src"));
        Path outRoot = Files.createDirectories(tempDir.resolve(caseName).resolve("out"));

        for (Map.Entry<String, String> e : javaFiles.entrySet()) {
            String rel = e.getKey();
            int idx = rel.lastIndexOf("/src/main/java/");
            assertTrue(idx >= 0, "产物路径应含 /src/main/java/：" + rel);
            Path target = srcRoot.resolve(rel.substring(idx + "/src/main/java/".length()));
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JDK 应提供 JavaCompiler（运行于 JRE 时缺失）");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<File> sources = javaFiles.keySet().stream()
                    .map(k -> srcRoot.resolve(k.substring(k.lastIndexOf("/src/main/java/")
                            + "/src/main/java/".length())).toFile())
                    .toList();
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);

            List<String> options = new ArrayList<>(List.of(
                    "-encoding", "UTF-8",
                    "-classpath", classpath(),
                    // JDK 23+ 默认不再扫描 classpath 上的注解处理器，Lombok 必须显式挂 processorpath
                    "-processorpath", lombokPath(),
                    "-d", outRoot.toString()
            ));
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outRoot.toFile()));

            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            List<String> diags = diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind() + " " + (d.getSource() == null ? "<none>" : d.getSource().getName())
                            + ":" + d.getLineNumber() + " " + d.getMessage(Locale.ROOT))
                    .toList();

            long classCount = 0;
            try (var walk = Files.walk(outRoot)) {
                classCount = walk.filter(p -> p.toString().endsWith(".class")).count();
            }
            return new CompiledArtifact(javaFiles.size(), (int) classCount, diags, ok, javaFiles);
        }
    }

    /**
     * 推导编译期 classpath。
     *
     * <p>首选从测试类加载器（surefire 的 IsolatedClassLoader 是 URLClassLoader）取全量 URL——
     * 这样模板引用任何已在测试 classpath 上的依赖都能编译，不需要逐个登记。
     * 若拿不到（例如未来切到非 URLClassLoader 的运行方式），退回锚点类反推，
     * 此时锚点清单就是「产物依赖白名单」，缺一个即编译红。
     */
    private static String classpath() {
        Set<String> paths = new LinkedHashSet<>();
        ClassLoader cl = GeneratedCodeCompileTest.class.getClassLoader();
        if (cl instanceof java.net.URLClassLoader ucl) {
            for (java.net.URL u : ucl.getURLs()) {
                try {
                    paths.add(Path.of(u.toURI()).toString());
                } catch (Exception ignored) {
                    // 非法 URL 直接跳过
                }
            }
        }
        if (paths.size() < CLASSPATH_ANCHORS.size()) {
            paths.clear();
            for (Class<?> anchor : CLASSPATH_ANCHORS) {
                String p = locationOf(anchor);
                if (p != null) {
                    paths.add(p);
                }
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String lombokPath() {
        String p = locationOf(Data.class);
        assertTrue(p != null, "Lombok 应在测试 classpath 上");
        return p;
    }

    private static String locationOf(Class<?> type) {
        try {
            CodeSource cs = type.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            URI uri = cs.getLocation().toURI();
            return Path.of(uri).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单次编译结果 */
    private record CompiledArtifact(int javaCount, int classCount, List<String> diagnostics,
                                    boolean ok, Map<String, String> sources) {
    }

    // ==================== 元数据夹具 ====================

    private static GenTable table(String tplCategory) {
        GenTable t = new GenTable();
        t.setId(1L);
        t.setTableName("biz_product");
        t.setTableComment("Product table");
        t.setClassName("BizProduct");
        t.setPackageName("com.pivotos.system");
        t.setModuleName("system");
        t.setBusinessName("product");
        t.setFunctionName("Product Management");
        t.setFunctionAuthor("PivotOS");
        t.setTplCategory(tplCategory);
        t.setGenType("0");
        return t;
    }

    private static List<GenTableColumn> mainColumns() {
        return List.of(
                column(1L, "id", "id", "bigint", "Long", 1, 0, 0, 0, 0, 0, "id"),
                column(2L, "product_name", "productName", "varchar(100)", "String", 0, 1, 1, 1, 1, 1, "product_name"),
                column(3L, "price", "price", "decimal(10,2)", "BigDecimal", 0, 1, 1, 1, 1, 0, "price"),
                column(4L, "create_time", "createTime", "datetime", "LocalDateTime", 0, 0, 0, 0, 0, 0, "create_time")
        );
    }

    private static GenTableColumn column(Long id, String columnName, String javaField, String columnType,
                                         String javaType, int isPk, int isRequired, int isInsert,
                                         int isEdit, int isList, int isQuery, String comment) {
        GenTableColumn c = new GenTableColumn();
        c.setId(id);
        c.setTableId(1L);
        c.setColumnName(columnName);
        c.setJavaField(javaField);
        c.setColumnType(columnType);
        c.setJavaType(javaType);
        c.setIsPk(isPk);
        c.setIsRequired(isRequired);
        c.setIsInsert(isInsert);
        c.setIsEdit(isEdit);
        c.setIsList(isList);
        c.setIsQuery(isQuery);
        c.setColumnComment(comment);
        c.setQueryType("EQ");
        c.setHtmlType("input");
        c.setSort(id.intValue());
        return c;
    }
}

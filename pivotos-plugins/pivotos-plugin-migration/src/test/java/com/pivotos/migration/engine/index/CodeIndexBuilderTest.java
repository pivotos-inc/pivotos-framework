package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码索引构建器测试（临时目录夹具，不碰真实仓库）。
 *
 * <p>覆盖点：分层分类（实现层 vs 上层）、符号表抽取（方法/字段/前端函数与 schema 字段）、
 * include 通配命中、构建产物目录排除、摘要抽取（S107 K5 口径：javadoc 续行也要命中）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@DisplayName("CodeIndexBuilder 测试")
class CodeIndexBuilderTest {

    @TempDir
    Path root;

    private CodeIndexBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CodeIndexBuilder(List.of(new JavaIndexParser(), new FrontendIndexParser()));
    }

    @Test
    @DisplayName("分层分类：Impl 归实现层，Controller/DTO/实体归上层")
    void classifyLayers() throws Exception {
        write("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java", """
                package com.pivotos.message.service.impl;

                /**
                 * 消息模板服务实现。
                 */
                @Service
                public class TemplateServiceImpl implements ITemplateService {
                    private String status;

                    public void deleteTemplate(Long id) {
                        System.out.println(id);
                    }
                }
                """);
        write("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/controller/MsgTemplateController.java", """
                package com.pivotos.message.controller;

                @RestController
                public class MsgTemplateController {
                    public void delete(Long id) {
                    }
                }
                """);
        write("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/domain/entity/MsgTemplate.java", """
                package com.pivotos.message.domain.entity;

                @TableName("msg_template")
                public class MsgTemplate {
                    private Long id;
                }
                """);
        write("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/api/dto/TemplateQuery.java", """
                package com.pivotos.message.api.dto;

                public class TemplateQuery {
                    private String name;
                }
                """);

        CodeIndexSnapshot snapshot = builder.build("fw", root.toString(),
                List.of("pivotos-plugins/**/src/main/java/**/*.java"));

        assertEquals(4, snapshot.size());
        assertEquals(CodeLayer.SERVICE_IMPL, layerOf(snapshot, "service/impl/TemplateServiceImpl.java"));
        assertEquals(CodeLayer.CONTROLLER, layerOf(snapshot, "controller/MsgTemplateController.java"));
        assertEquals(CodeLayer.ENTITY, layerOf(snapshot, "domain/entity/MsgTemplate.java"));
        assertEquals(CodeLayer.DTO, layerOf(snapshot, "api/dto/TemplateQuery.java"));
    }

    @Test
    @DisplayName("符号表：抽取方法签名与行号，并带上 imports")
    void symbolTableExtractsMethodsAndImports() throws Exception {
        write("pivotos-plugins/pivotos-plugin-ai/src/main/java/com/pivotos/ai/service/impl/AiToolServiceImpl.java", """
                package com.pivotos.ai.service.impl;

                import java.util.List;
                import com.pivotos.common.core.exception.ServiceException;

                /**
                 * AI 工具服务实现。
                 */
                @Service
                public class AiToolServiceImpl {

                    public void updateRoleWhitelist(Long toolId, List<String> roles) {
                    }
                }
                """);

        CodeIndexSnapshot snapshot = builder.build("fw", root.toString(),
                List.of("pivotos-plugins/**/src/main/java/**/*.java"));
        FileSymbolTable table = snapshot.symbolTables().get(
                "pivotos-plugins/pivotos-plugin-ai/src/main/java/com/pivotos/ai/service/impl/AiToolServiceImpl.java");

        assertNotNull(table);
        assertEquals("AiToolServiceImpl", table.typeName());
        assertEquals("com.pivotos.ai.service.impl", table.packageName());
        assertTrue(table.imports().contains("java.util.List"));
        assertTrue(table.imports().contains("com.pivotos.common.core.exception.ServiceException"));
        assertTrue(table.symbolNames().contains("updateRoleWhitelist"));
        assertEquals(12, table.symbols().stream()
                .filter(s -> "updateRoleWhitelist".equals(s.name())).findFirst().orElseThrow().line());
        // 摘要须命中 javadoc 正文（K5：不能只匹配 /** 开行，也不能把 * 续行当无效）
        assertEquals("AI 工具服务实现。", table.summary());
    }

    @Test
    @DisplayName("前端索引：vue 归页面层，api/*.ts 归 TS_API，抽取函数名与 schema 字段")
    void frontendIndex() throws Exception {
        write("apps/admin/src/api/system/post.ts", """
                import request from '@/utils/request';

                /** 岗位导出 */
                export function exportPosts(data: unknown): Promise<Blob> {
                  return request.post('/system/post/export', data);
                }
                """);
        write("apps/admin/src/views/system/post/index.vue", """
                <template><div /></template>
                <script setup lang="ts">
                const formSchemas = computed(() => [
                  { field: 'postCode', label: '岗位编码' },
                ]);
                </script>
                """);

        CodeIndexSnapshot snapshot = builder.build("ui", root.toString(),
                List.of("apps/admin/src/**/*.ts", "apps/admin/src/**/*.vue"));

        assertEquals(2, snapshot.size());
        assertEquals(CodeLayer.TS_API, layerOf(snapshot, "apps/admin/src/api/system/post.ts"));
        assertEquals(CodeLayer.VUE_PAGE, layerOf(snapshot, "apps/admin/src/views/system/post/index.vue"));

        FileSymbolTable api = snapshot.symbolTables().get("apps/admin/src/api/system/post.ts");
        assertTrue(api.symbolNames().contains("exportPosts"));
        FileSymbolTable page = snapshot.symbolTables().get("apps/admin/src/views/system/post/index.vue");
        assertTrue(page.symbolNames().contains("postCode"));
    }

    @Test
    @DisplayName("include 通配与目标目录排除：target/ 与 node_modules/ 不进索引")
    void excludesBuildOutput() throws Exception {
        write("pivotos-plugins/pivotos-plugin-ai/src/main/java/com/pivotos/ai/A.java", "package a;\npublic class A {}");
        write("pivotos-plugins/pivotos-plugin-ai/target/classes/com/pivotos/ai/A.class.java",
                "package a;\npublic class A {}");
        write("apps/admin/src/api/system/user.ts", "export function fetchUsers() {}");
        write("apps/admin/node_modules/vue/index.ts", "export function fake() {}");

        CodeIndexSnapshot snapshot = builder.build("fw", root.toString(),
                List.of("pivotos-plugins/**/src/main/java/**/*.java",
                        "apps/admin/src/**/*.ts"));

        assertEquals(2, snapshot.size());
        assertTrue(snapshot.entries().stream().noneMatch(e -> e.relativePath().contains("/target/")));
        assertTrue(snapshot.entries().stream().noneMatch(e -> e.relativePath().contains("node_modules")));
        assertTrue(snapshot.entries().stream().anyMatch(e -> e.relativePath().endsWith("user.ts") && e.layer() == CodeLayer.TS_API));
    }

    @Test
    @DisplayName("索引条目携带模块名与关键符号，供粗定位喂入")
    void entryCarriesModuleAndSymbols() throws Exception {
        write("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java", """
                package com.pivotos.message.service.impl;

                public class TemplateServiceImpl {
                    public void deleteTemplate(Long id) {
                    }

                    public String render(String tpl) {
                        return tpl;
                    }
                }
                """);

        CodeIndexSnapshot snapshot = builder.build("fw", root.toString(),
                List.of("pivotos-plugins/**/src/main/java/**/*.java"));
        CodeFileEntry entry = snapshot.entries().get(0);

        assertEquals("pivotos-plugins/pivotos-plugin-message", entry.moduleName());
        assertEquals("TemplateServiceImpl", entry.typeName());
        assertTrue(entry.symbols().contains("deleteTemplate"));
        assertTrue(entry.symbols().contains("render"));
    }

    // ---------------- 辅助 ----------------

    private CodeLayer layerOf(CodeIndexSnapshot snapshot, String suffix) {
        return snapshot.entries().stream()
                .filter(e -> e.relativePath().endsWith(suffix))
                .map(CodeFileEntry::layer)
                .findFirst()
                .orElseThrow(() -> new AssertionError("索引中无该文件：" + suffix));
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

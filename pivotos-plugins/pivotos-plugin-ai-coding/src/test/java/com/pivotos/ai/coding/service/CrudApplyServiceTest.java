package com.pivotos.ai.coding.service;

import tools.jackson.databind.json.JsonMapper;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CrudApplyService 单测（S43 / 2.2-F13）：Flyway 版本扫描、pages.json 幂等注册、
 * 落盘根推导、路径白名单、lint 门禁。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrudApplyService unit tests")
class CrudApplyServiceTest {

    @Mock
    private ArtifactLinter artifactLinter;

    @Mock
    private AssemblyPatcher assemblyPatcher;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private CrudApplyService crudApplyService;

    @BeforeEach
    void setUp() {
        crudApplyService = new CrudApplyService(artifactLinter, assemblyPatcher, jsonMapper);
    }

    // ==================== Flyway 版本扫描 ====================

    @Test
    @DisplayName("nextFlywayVersion - 全仓取最大版本末段 +1")
    void testNextFlywayVersion(@TempDir Path root) throws Exception {
        writeMigration(root, "pivotos-plugin-system", "V1.2.17__sys_job_log.sql");
        writeMigration(root, "pivotos-plugin-ai", "V1.1.7__ai_provider_menu.sql");

        assertEquals("1.2.18", crudApplyService.nextFlywayVersion(root));
    }

    @Test
    @DisplayName("nextFlywayVersion - 空仓回落 1.0.0")
    void testNextFlywayVersionEmpty(@TempDir Path root) {
        assertEquals("1.0.0", crudApplyService.nextFlywayVersion(root));
    }

    // ==================== pages.json 注册 ====================

    @Test
    @DisplayName("registerPagesJson - 插入三页且二次调用幂等")
    void testRegisterPagesJsonIdempotent(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("pivotos-app/src"));
        Files.writeString(workspace.resolve("pivotos-app/src/pages.json"), samplePagesJson());

        crudApplyService.registerPagesJson(workspace, "biz", "book", "图书管理");
        crudApplyService.registerPagesJson(workspace, "biz", "book", "图书管理");

        String content = Files.readString(workspace.resolve("pivotos-app/src/pages.json"));
        assertEquals(1, countOccurrences(content, "\"path\": \"biz/book/list\""));
        assertEquals(1, countOccurrences(content, "\"path\": \"biz/book/form\""));
        assertEquals(1, countOccurrences(content, "\"path\": \"biz/book/detail\""));
        assertTrue(content.contains("\"navigationBarTitleText\": \"图书管理\""));
        // 原有 demo 页保留
        assertTrue(content.contains("\"path\": \"demo/demo\""));
    }

    // ==================== vite 代理补丁 ====================

    @Test
    @DisplayName("patchViteProxy - admin 带 bypass、app 简式，二次调用幂等")
    void testPatchViteProxyIdempotent(@TempDir Path workspace) throws Exception {
        Path adminCfg = workspace.resolve("pivotos-ui/apps/admin/vite.config.ts");
        Files.createDirectories(adminCfg.getParent());
        Files.writeString(adminCfg, "export default defineConfig({\n"
                + "  server: {\n"
                + "      proxy: {\n"
                + "        '/system': {},\n"
                + "      },\n"
                + "  },\n"
                + "});\n");
        Path appCfg = workspace.resolve("pivotos-app/vite.config.ts");
        Files.createDirectories(appCfg.getParent());
        Files.writeString(appCfg, "      proxy: {\n"
                + "        '/system': { target: apiTarget, changeOrigin: true },\n"
                + "      },\n");

        Path adminRel = Path.of("pivotos-ui/apps/admin/vite.config.ts");
        Path appRel = Path.of("pivotos-app/vite.config.ts");
        crudApplyService.patchViteProxy(workspace, "biz", adminRel, true);
        crudApplyService.patchViteProxy(workspace, "biz", appRel, false);
        crudApplyService.patchViteProxy(workspace, "biz", adminRel, true);

        String admin = Files.readString(adminCfg);
        String app = Files.readString(appCfg);
        assertEquals(1, countOccurrences(admin, "'/biz':"));
        assertTrue(admin.contains("bypass(req)"));
        assertEquals(1, countOccurrences(app, "'/biz': { target: apiTarget, changeOrigin: true },"));
    }

    // ==================== apply 端到端（落盘根推导 + 迁移命名） ====================

    @Test
    @DisplayName("apply - 产物按根落盘、迁移取 V1.2.18、pages.json 注册")
    void testApplyHappyPath(@TempDir Path workspace) throws Exception {
        Path frameworkRoot = workspace.resolve("pivotos-framework");
        Files.createDirectories(frameworkRoot.resolve("pivotos-plugins"));
        Files.writeString(frameworkRoot.resolve("pivotos-plugins/pom.xml"), "<project/>");
        writeMigration(frameworkRoot, "pivotos-plugin-system", "V1.2.17__sys_job_log.sql");
        Files.createDirectories(workspace.resolve("pivotos-app/src"));
        Files.writeString(workspace.resolve("pivotos-app/src/pages.json"), samplePagesJson());

        when(assemblyPatcher.resolveFrameworkRoot()).thenReturn(frameworkRoot);
        when(artifactLinter.lint(any())).thenReturn(List.of());

        Map<String, String> files = new LinkedHashMap<>();
        files.put("pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system/domain/entity/Book.java",
                "package com.pivotos.system.domain.entity;");
        files.put("pivotos-ui/apps/admin/src/views/biz/book/index.vue", "<template/>");
        files.put("pivotos-app/src/pages-gen/biz/book/list.vue", "<template/>");
        files.put("sql/V20260810_154500__gen_biz_book.sql", "CREATE TABLE biz_book(id BIGINT);");

        CodingSession session = buildSession(files);
        crudApplyService.apply(session);

        assertTrue(Files.exists(frameworkRoot.resolve(
                "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system/domain/entity/Book.java")));
        assertTrue(Files.exists(workspace.resolve("pivotos-ui/apps/admin/src/views/biz/book/index.vue")));
        assertTrue(Files.exists(workspace.resolve("pivotos-app/src/pages-gen/biz/book/list.vue")));
        // 迁移写进目标插件 migration 目录，版本号为全仓最大 +1
        assertTrue(Files.exists(frameworkRoot.resolve(
                "pivotos-plugins/pivotos-plugin-system/src/main/resources/db/migration/V1.2.18__gen_biz_book.sql")));
        // sql/ 目录不落盘
        assertFalse(Files.exists(workspace.resolve("pivotos-framework/sql")));
        assertTrue(Files.readString(workspace.resolve("pivotos-app/src/pages.json"))
                .contains("\"path\": \"biz/book/list\""));
    }

    @Test
    @DisplayName("apply - lint 违规门禁拦截")
    void testApplyLintRejected(@TempDir Path workspace) {
        when(artifactLinter.lint(any())).thenReturn(List.of("R1: System.out.println"));
        CodingSession session = buildSession(Map.of(
                "pivotos-plugins/pivotos-plugin-system/src/main/java/X.java", "class X {}"));
        ServiceException ex = assertThrows(ServiceException.class, () -> crudApplyService.apply(session));
        assertNotNull(ex.getCode());
    }

    @Test
    @DisplayName("apply - 越界路径白名单拦截")
    void testApplyPathRejected(@TempDir Path workspace) {
        when(artifactLinter.lint(any())).thenReturn(List.of());
        when(assemblyPatcher.resolveFrameworkRoot()).thenReturn(workspace.resolve("pivotos-framework"));
        CodingSession session = buildSession(Map.of(
                "pivotos-docs/项目文档库/x.md", "x"));
        ServiceException ex = assertThrows(ServiceException.class, () -> crudApplyService.apply(session));
        assertNotNull(ex.getCode());
    }

    // ==================== Helper ====================

    private CodingSession buildSession(Map<String, String> files) {
        CodingSession session = new CodingSession();
        session.setId(1L);
        session.setModuleName("biz");
        session.setBusinessName("book");
        session.setFunctionName("图书管理");
        session.setTableName("biz_book");
        session.setGeneratedFilesJson(jsonMapper.writeValueAsString(files));
        return session;
    }

    private void writeMigration(Path frameworkRoot, String plugin, String fileName) throws Exception {
        Path dir = frameworkRoot.resolve("pivotos-plugins/" + plugin + "/src/main/resources/db/migration");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), "-- x");
    }

    private static String samplePagesJson() {
        return "{\n"
                + "\t\"subPackages\": [\n"
                + "\t\t{\n"
                + "\t\t\t\"root\": \"pages-gen\",\n"
                + "\t\t\t\"pages\": [\n"
                + "\t\t\t\t{\n"
                + "\t\t\t\t\t\"path\": \"demo/demo\",\n"
                + "\t\t\t\t\t\"style\": {\n"
                + "\t\t\t\t\t\t\"navigationBarTitleText\": \"生成器示例\"\n"
                + "\t\t\t\t\t}\n"
                + "\t\t\t\t}\n"
                + "\t\t\t]\n"
                + "\t\t}\n"
                + "\t]\n"
                + "}\n";
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}

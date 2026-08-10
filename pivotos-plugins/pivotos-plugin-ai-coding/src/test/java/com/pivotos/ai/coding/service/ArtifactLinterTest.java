package com.pivotos.ai.coding.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ArtifactLinter.
 */
@DisplayName("ArtifactLinter unit tests")
class ArtifactLinterTest {

    private final ArtifactLinter linter = new ArtifactLinter();

    @Test
    @DisplayName("clean skeleton passes")
    void testCleanPass() {
        Map<String, String> files = Map.of(
                "pivotos-plugins/pivotos-plugin-asset/pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>",
                "pivotos-plugins/pivotos-plugin-asset-api/src/main/java/com/pivotos/asset/api/constant/AssetErrorCode.java",
                "package com.pivotos.asset.api.constant; public enum AssetErrorCode {}");
        assertTrue(linter.lint(files).isEmpty());
    }

    @Test
    @DisplayName("ThreadLocal violation detected")
    void testThreadLocal() {
        Map<String, String> files = Map.of("a/A.java", "private ThreadLocal<String> ctx = new ThreadLocal<>();");
        assertTrue(linter.lint(files).stream().anyMatch(v -> v.startsWith("R1")));
    }

    @Test
    @DisplayName("raw thread violation detected")
    void testRawThread() {
        Map<String, String> files = Map.of("a/A.java", "new Thread(() -> {}).start();");
        assertTrue(linter.lint(files).stream().anyMatch(v -> v.startsWith("R2")));
    }

    @Test
    @DisplayName("fastjson 1.x detected, fastjson2 clean")
    void testFastjson() {
        assertTrue(linter.lint(Map.of("a/A.java", "import com.alibaba.fastjson.JSON;"))
                .stream().anyMatch(v -> v.startsWith("R3")));
        assertTrue(linter.lint(Map.of("a/A.java", "import com.alibaba.fastjson2.JSON;")).isEmpty());
    }

    @Test
    @DisplayName("AUTO_INCREMENT detected in .sql only; README mentioning it is clean")
    void testAutoIncrement() {
        assertTrue(linter.lint(Map.of("sql/init.sql", "id bigint auto_increment"))
                .stream().anyMatch(v -> v.startsWith("R5")));
        // 文档产物提到规则条文本身不命中（S42 实测误报：flyway README 含 AUTO_INCREMENT 字样）
        assertTrue(linter.lint(Map.of("db/migration/README.md", "禁 AUTO_INCREMENT，主键雪花")).isEmpty());
    }

    @Test
    @DisplayName("cross-plugin impl import detected, -api import clean")
    void testCrossPlugin() {
        assertTrue(linter.lint(Map.of("a/A.java", "import com.pivotos.system.service.UserService;"))
                .stream().anyMatch(v -> v.startsWith("R6")));
        assertTrue(linter.lint(Map.of("a/A.java", "import com.pivotos.system.api.facade.IUserFacade;")).isEmpty());
    }

    @Test
    @DisplayName("R6 同插件内部引用不误报（S43：CRUD 产物落既有插件）")
    void testSamePluginImportClean() {
        // 产物落在 pivotos-plugin-system，引用 com.pivotos.system.* 属同插件内部
        assertTrue(linter.lint(Map.of(
                "pivotos-plugins/pivotos-plugin-system/src/main/java/com/pivotos/system/controller/BookController.java",
                "package com.pivotos.system.controller; import com.pivotos.system.service.BookService;")).isEmpty());
        // 产物落在 pivotos-plugin-asset，引用 system 实现包仍命中
        assertTrue(linter.lint(Map.of(
                "pivotos-plugins/pivotos-plugin-asset/src/main/java/com/pivotos/asset/controller/AssetController.java",
                "package com.pivotos.asset.controller; import com.pivotos.system.service.UserService;"))
                .stream().anyMatch(v -> v.startsWith("R6")));
    }

    @Test
    @DisplayName("path traversal detected")
    void testPathTraversal() {
        assertTrue(linter.lint(Map.of("../evil.java", "x"))
                .stream().anyMatch(v -> v.startsWith("R7")));
    }
}

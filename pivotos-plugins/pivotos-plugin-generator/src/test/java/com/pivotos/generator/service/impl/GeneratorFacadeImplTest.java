package com.pivotos.generator.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模块包名回落单测（S43）：插件目录存在用 com.pivotos.{module}，
 * 不存在回落 com.pivotos.system，避免产物落成死代码。
 */
@DisplayName("GeneratorFacadeImpl module fallback tests")
class GeneratorFacadeImplTest {

    private String originalUserDir;

    @AfterEach
    void restoreUserDir() {
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    @DisplayName("插件目录存在 → 保留 com.pivotos.{module}")
    void testExistingModuleKeepsPackage(@TempDir Path tempDir) throws Exception {
        prepareFrameworkRoot(tempDir);
        Files.createDirectories(tempDir.resolve("pivotos-plugins/pivotos-plugin-asset"));

        assertEquals("com.pivotos.asset", GeneratorFacadeImpl.resolvePackageName("asset"));
    }

    @Test
    @DisplayName("插件目录不存在 → 回落 com.pivotos.system")
    void testUnknownModuleFallsBackToSystem(@TempDir Path tempDir) throws Exception {
        prepareFrameworkRoot(tempDir);

        assertEquals("com.pivotos.system", GeneratorFacadeImpl.resolvePackageName("biz"));
    }

    /** 搭一个最小 framework 根（含 pivotos-plugins/pom.xml），并把 user.dir 指过去 */
    private void prepareFrameworkRoot(Path root) throws Exception {
        originalUserDir = System.getProperty("user.dir");
        Files.createDirectories(root.resolve("pivotos-plugins"));
        Files.writeString(root.resolve("pivotos-plugins/pom.xml"), "<project/>");
        System.setProperty("user.dir", root.toString());
    }
}

package com.pivotos.ai.coding.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AssemblyPatcher (temp-dir fake framework root).
 */
@DisplayName("AssemblyPatcher unit tests")
class AssemblyPatcherTest {

    private final AssemblyPatcher patcher = new AssemblyPatcher();

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("pivotos-plugins"));
        Files.writeString(root.resolve("pivotos-plugins/pom.xml"), """
                <project>
                    <modules>
                        <module>pivotos-plugin-system</module>
                    </modules>
                </project>
                """);
        Files.createDirectories(root.resolve("pivotos-dependencies"));
        Files.writeString(root.resolve("pivotos-dependencies/pom.xml"), """
                <project>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.pivotos</groupId>
                                <artifactId>pivotos-plugin-system</artifactId>
                                <version>${project.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.createDirectories(root.resolve("pivotos-admin-server"));
        Files.writeString(root.resolve("pivotos-admin-server/pom.xml"), """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>com.pivotos</groupId>
                            <artifactId>pivotos-plugin-system</artifactId>
                        </dependency>
                    </dependencies>

                    <dependencyManagement>
                        <dependencies>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.createDirectories(root.resolve("pivotos-admin-server/src/main/java/com/pivotos/server"));
        Files.writeString(root.resolve(
                "pivotos-admin-server/src/main/java/com/pivotos/server/PivotOsAdminApplication.java"),
                """
                @SpringBootApplication(scanBasePackages = {"com.pivotos.server", "com.pivotos.system"})
                public class PivotOsAdminApplication {}
                """);
    }

    @Test
    @DisplayName("patch inserts modules / BOM entries / dependency / scanBasePackages")
    void testPatchSuccess() throws Exception {
        List<String> changed = patcher.patch(root, "asset", "资产管理");
        assertEquals(4, changed.size());

        String pluginsPom = Files.readString(root.resolve("pivotos-plugins/pom.xml"));
        assertTrue(pluginsPom.contains("<module>pivotos-plugin-asset-api</module>"));
        assertTrue(pluginsPom.contains("<module>pivotos-plugin-asset</module>"));

        // BOM：双模块版本仲裁插进 dependencyManagement
        String bomPom = Files.readString(root.resolve("pivotos-dependencies/pom.xml"));
        assertTrue(bomPom.contains("<artifactId>pivotos-plugin-asset-api</artifactId>"));
        assertTrue(bomPom.contains("<artifactId>pivotos-plugin-asset</artifactId>"));
        assertTrue(bomPom.indexOf("<artifactId>pivotos-plugin-asset</artifactId>")
                < bomPom.indexOf("</dependencyManagement>"));

        // admin-server：依赖必须落在顶层 dependencies（dependencyManagement 之前）
        String adminPom = Files.readString(root.resolve("pivotos-admin-server/pom.xml"));
        int depIdx = adminPom.indexOf("<artifactId>pivotos-plugin-asset</artifactId>");
        assertTrue(depIdx > 0);
        assertTrue(depIdx < adminPom.indexOf("<dependencyManagement>"),
                "依赖不得落进 dependencyManagement（S42 实测踩坑）");

        String appClass = Files.readString(root.resolve(
                "pivotos-admin-server/src/main/java/com/pivotos/server/PivotOsAdminApplication.java"));
        assertTrue(appClass.contains("\"com.pivotos.asset\""));
        assertTrue(appClass.contains("\"com.pivotos.system\", \"com.pivotos.asset\""));
    }

    @Test
    @DisplayName("patch is idempotent (second run changes nothing)")
    void testPatchIdempotent() throws Exception {
        patcher.patch(root, "asset", "资产管理");
        String before1 = Files.readString(root.resolve("pivotos-plugins/pom.xml"));
        String before2 = Files.readString(root.resolve("pivotos-admin-server/pom.xml"));
        String before3 = Files.readString(root.resolve(
                "pivotos-admin-server/src/main/java/com/pivotos/server/PivotOsAdminApplication.java"));

        List<String> changed = patcher.patch(root, "asset", "资产管理");
        assertTrue(changed.isEmpty());
        assertEquals(before1, Files.readString(root.resolve("pivotos-plugins/pom.xml")));
        assertEquals(before2, Files.readString(root.resolve("pivotos-admin-server/pom.xml")));
        assertEquals(before3, Files.readString(root.resolve(
                "pivotos-admin-server/src/main/java/com/pivotos/server/PivotOsAdminApplication.java")));
    }

    @Test
    @DisplayName("resolveFrameworkRoot walks up to dir containing pivotos-plugins/pom.xml")
    void testResolveFrameworkRoot() throws Exception {
        Path nested = root.resolve("pivotos-admin-server/target");
        Files.createDirectories(nested);
        String original = System.getProperty("user.dir");
        System.setProperty("user.dir", nested.toString());
        try {
            assertEquals(root.toAbsolutePath(), patcher.resolveFrameworkRoot());
        } finally {
            System.setProperty("user.dir", original);
        }
    }
}

package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.config.ModifyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门禁命令分派测试（A4-2 / S111）。
 *
 * <p>核心守卫：命令必须按<b>仓库根</b>分派，不能按路径前缀——修改型拿到的是仓内相对路径，
 * 按前缀判断会让前端改动掉进「跳过」分支，编译门禁静默失效（等于没闸）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("ModifyGate 命令分派 测试")
class ModifyGateTest {

    private final ModifyGate gate = new ModifyGate(new ModifyProperties());

    @Test
    @DisplayName("后端仓：解析为 mvn -pl <module>")
    void backendCommand() {
        var command = gate.resolveCommand(Path.of("/repos/pivotos-framework"),
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/X.java");
        assertNotNull(command);
        assertTrue(command.args().stream().anyMatch(a -> a.contains("pivotos-plugin-message")),
                "须带 -pl 模块：" + command.args());
        assertTrue(command.args().stream().anyMatch(a -> a.endsWith("mvn")),
                "须用本机固定 Maven 路径：" + command.args());
        assertTrue(command.env().containsKey("JAVA_HOME"));
    }

    @Test
    @DisplayName("前端仓：解析为 pnpm typecheck，且固定 PATH / 清空 NODE_OPTIONS")
    void frontendCommand() {
        var command = gate.resolveCommand(Path.of("/repos/pivotos-ui"), "apps/admin/src/api/system/post.ts");
        assertNotNull(command, "前端改动必须有门禁命令，不能静默跳过");
        assertEquals("/bin/sh", command.args().get(0));
        assertEquals("-c", command.args().get(1));
        assertEquals("pnpm typecheck", command.args().get(2));
        assertEquals("", command.env().get("NODE_OPTIONS"), "NODE_OPTIONS 须清空（踩坑 28）");
        assertTrue(command.env().get("PATH").startsWith("/usr/local/bin"));
    }

    @Test
    @DisplayName("移动端：无门禁命令，显式跳过")
    void mobileSkipped() {
        assertNull(gate.resolveCommand(Path.of("/repos/pivotos-app"), "src/api/system/user.ts"));
    }

    @Test
    @DisplayName("后端仓但路径非模块内：不臆造命令")
    void backendUnknown() {
        assertNull(gate.resolveCommand(Path.of("/repos/pivotos-framework"), "pom.xml"));
    }

    @Test
    @DisplayName("自定义后端命令非 maven 形式：整体执行，不再拼到 mavenPath 后面")
    void customBackendCommand() {
        ModifyProperties props = new ModifyProperties();
        props.getGate().setFwCommand("/bin/false");
        var command = new ModifyGate(props).resolveCommand(Path.of("/repos/pivotos-framework"),
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/X.java");
        assertNotNull(command);
        assertEquals(List.of("/bin/sh", "-c", "/bin/false"), command.args(),
                "拼到 mvn 后面会变成「mvn /bin/false」→ 退化为 no-POM 报错，门禁失败原因失真");
    }
}

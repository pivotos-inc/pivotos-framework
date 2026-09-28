package com.pivotos.ai.coding.modify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 落盘路径白名单测试（修改型与生成型共用同一道闸门，7009 越界拒绝）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("CodingPathWhitelist 测试")
class CodingPathWhitelistTest {

    @Test
    @DisplayName("白名单内路径放行")
    void allowed() {
        assertTrue(CodingPathWhitelist.allows(
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java"));
        assertTrue(CodingPathWhitelist.allows(
                "pivotos-plugins/pivotos-plugin-message/src/main/resources/db/migration/V1.2.47__x.sql"));
        assertTrue(CodingPathWhitelist.allows("pivotos-ui/apps/admin/src/api/system/post.ts"));
        assertTrue(CodingPathWhitelist.allows("pivotos-ui/apps/admin/src/views/system/post/index.vue"));
        assertTrue(CodingPathWhitelist.allows("pivotos-app/src/api/system/user.ts"));
    }

    @Test
    @DisplayName("越界路径拒绝：路径穿越、非白名单目录、null")
    void rejected() {
        assertFalse(CodingPathWhitelist.allows("../../etc/passwd"));
        assertFalse(CodingPathWhitelist.allows("pivotos-plugins/pivotos-plugin-x/../../../secret"));
        assertFalse(CodingPathWhitelist.allows("pom.xml"));
        assertFalse(CodingPathWhitelist.allows("pivotos-ui/package.json"));
        assertFalse(CodingPathWhitelist.allows("pivotos-plugins/pivotos-plugin-x/src/test/java/X.java"),
                "测试源码目录不在白名单内");
        assertFalse(CodingPathWhitelist.allows(null));
    }

    @Test
    @DisplayName("仓内路径判定：定位产出是仓内相对路径，须按仓库根补回基准")
    void allowedInRepo() {
        Path fw = Path.of("/repos/pivotos-framework");
        Path ui = Path.of("/repos/pivotos-ui");
        Path app = Path.of("/repos/pivotos-app");
        // 后端：仓内路径即白名单基准，不加前缀
        assertTrue(CodingPathWhitelist.allowsInRepo(fw,
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/X.java"));
        // 前端/移动端：仓内路径需补仓名前缀（首轮 E2E 的 I6/I7/I8 正是被这道基准差误杀）
        assertTrue(CodingPathWhitelist.allowsInRepo(ui, "apps/admin/src/api/system/post.ts"));
        assertTrue(CodingPathWhitelist.allowsInRepo(ui, "apps/admin/src/views/system/post/index.vue"));
        assertTrue(CodingPathWhitelist.allowsInRepo(app, "src/api/system/user.ts"));
    }

    @Test
    @DisplayName("仓内路径判定：闸门强度不变，越界照样拒绝")
    void rejectedInRepo() {
        Path ui = Path.of("/repos/pivotos-ui");
        Path fw = Path.of("/repos/pivotos-framework");
        assertFalse(CodingPathWhitelist.allowsInRepo(ui, "apps/admin/src/router/index.ts"),
                "router 目录不在白名单（api|views）内");
        assertFalse(CodingPathWhitelist.allowsInRepo(ui, "package.json"));
        assertFalse(CodingPathWhitelist.allowsInRepo(ui, "../../etc/passwd"));
        assertFalse(CodingPathWhitelist.allowsInRepo(fw, "pom.xml"));
        assertFalse(CodingPathWhitelist.allowsInRepo(ui, null));
        assertFalse(CodingPathWhitelist.allowsInRepo(null, "apps/admin/src/api/x.ts"));
    }

    @Test
    @DisplayName("仓根目录名（门禁命令分派依据）")
    void repoDirName() {
        assertEquals("pivotos-ui", CodingPathWhitelist.dirName(Path.of("/repos/pivotos-ui")));
        assertEquals("pivotos-framework", CodingPathWhitelist.dirName(Path.of("/repos/pivotos-framework")));
        assertEquals("", CodingPathWhitelist.dirName(null));
    }

    @Test
    @DisplayName("后端模块推断（编译门禁 -pl 用）")
    void backendModule() {
        assertEquals("pivotos-plugins/pivotos-plugin-message", CodingPathWhitelist.backendModuleOf(
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/X.java"));
        assertNull(CodingPathWhitelist.backendModuleOf("pivotos-ui/apps/admin/src/api/x.ts"));
        assertNull(CodingPathWhitelist.backendModuleOf(null));
    }
}

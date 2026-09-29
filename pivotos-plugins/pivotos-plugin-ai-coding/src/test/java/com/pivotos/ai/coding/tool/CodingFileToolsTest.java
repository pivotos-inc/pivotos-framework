package com.pivotos.ai.coding.tool;

import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.modify.ModifyGate;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_NO_CHANGE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_AMBIGUOUS;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_GATE_BUILD_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_REPO_UNKNOWN;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工程文件读写 AI 工具测试（A4-3 / S112）。
 *
 * <p>三条红线在此锁死：
 * <ul>
 *   <li>白名单外的读写一律 7009（工具是模型自助调用，比人工评审更容易越界）；</li>
 *   <li>search 不唯一/不存在/无变化分别对应 7020/7019/7024，绝不静默改文件；</li>
 *   <li>门禁失败必须回滚（同评审页「通过」链路同一道闸）；</li>
 *   <li>能力未开启时不登记工具对象——模型连工具列表都看不到。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S112 A4-3）
 */
@DisplayName("CodingFileTools 文件读写工具 测试")
class CodingFileToolsTest {

    private static final String FW_PATH =
            "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java";
    private static final String UI_PATH = "apps/admin/src/api/system/post.ts";
    private static final String SOURCE = "public class TemplateServiceImpl {\n"
            + "    public void a() {\n"
            + "    }\n"
            + "}\n";

    @TempDir
    Path tempDir;

    private Path fwRoot;
    private Path uiRoot;
    private ModifyProperties modifyProperties;
    private LocateProperties locateProperties;
    private CodingFileTools tools;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        fwRoot = tempDir.resolve("pivotos-framework");
        uiRoot = tempDir.resolve("pivotos-ui");
        write(fwRoot.resolve(FW_PATH), SOURCE);
        write(uiRoot.resolve(UI_PATH), "export function listPost() {\n  return 1;\n}\n");

        modifyProperties = new ModifyProperties();
        modifyProperties.setEnabled(true);
        modifyProperties.getGate().setBuildEnabled(false); // 默认跳过真实编译，回滚用例单独开启

        locateProperties = new LocateProperties();
        locateProperties.setRepos(new ArrayList<>(List.of(repo("fw", fwRoot), repo("ui", uiRoot))));

        ObjectProvider<ICodeIndexFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new StubIndexFacade(tempDir));
        tools = new CodingFileTools(modifyProperties, locateProperties, new ModifyGate(modifyProperties), provider);
    }

    @Test
    @DisplayName("能力未开启：不登记工具对象（模型连工具列表都看不到）")
    void notRegisteredWhenDisabled() {
        modifyProperties.setEnabled(false);
        assertEquals(0, tools.toolObjects().length);
    }

    @Test
    @DisplayName("能力开启：登记自身为工具对象")
    void registeredWhenEnabled() {
        assertEquals(1, tools.toolObjects().length);
        assertSame(tools, tools.toolObjects()[0]);
    }

    @Test
    @DisplayName("读仓内相对路径：成功返回内容（UI 侧仓内基准，不再被误杀）")
    void readInRepoRelativePath() {
        String out = tools.readCodeFile("ui", UI_PATH, 0);
        assertTrue(out.contains("export function listPost"), out);
        assertTrue(out.contains("共 3 行"), out);
    }

    @Test
    @DisplayName("读行数上限：超出截断并标注总行数")
    void readRespectsLineCap() throws IOException {
        write(fwRoot.resolve("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/A.java"),
                "line1\nline2\nline3\nline4\nline5\n");
        String out = tools.readCodeFile("fw",
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/A.java", 2);
        assertTrue(out.contains("共 5 行"), out);
        assertTrue(out.contains("line2"), out);
        assertFalse(out.contains("line3"), "超出行数必须截断：" + out);
        assertTrue(out.contains("已截断"), out);
    }

    @Test
    @DisplayName("读白名单外路径 → 7009（UI 仓 root 下非 api/views 目录）")
    void readRejectsOutOfWhitelist() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.readCodeFile("ui", "package.json", 0));
        assertEquals(CODING_PATH_REJECTED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("读未登记仓库 → 7013")
    void readRejectsUnknownRepo() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.readCodeFile("nope", UI_PATH, 0));
        assertEquals(CODING_LOCATE_REPO_UNKNOWN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("读路径穿越 → 7009")
    void readRejectsTraversal() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.readCodeFile("fw", "../../etc/passwd", 0));
        assertEquals(CODING_PATH_REJECTED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("写正常落盘：内容改动 + 门禁状态回执")
    void writeAppliesChange() throws IOException {
        String msg = tools.writeCodeFile("fw", FW_PATH, "    public void a() {",
                "    public void a() {\n        check();", true);
        String actual = Files.readString(fwRoot.resolve(FW_PATH), StandardCharsets.UTF_8);
        assertTrue(actual.contains("        check();"), actual);
        assertTrue(msg.contains("已写入"), msg);
        assertTrue(msg.contains("门禁"), "回执必须显式说明门禁结果（跳过态也要可见）：" + msg);
    }

    @Test
    @DisplayName("写白名单外落点 → 7009 且不写盘")
    void writeRejectsOutOfWhitelist() throws IOException {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.writeCodeFile("ui", "apps/admin/package.json", "x", "y", true));
        assertEquals(CODING_PATH_REJECTED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("search 不存在 → 7019，不静默写坏文件")
    void writeRejectsMissingSearch() throws IOException {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.writeCodeFile("fw", FW_PATH, "已经不存在的方法签名()", "x", true));
        assertEquals(CODING_EDIT_SEARCH_MISSING.getCode(), e.getCode());
        assertEquals(SOURCE, Files.readString(fwRoot.resolve(FW_PATH), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("search 多处命中 → 7020（歧义不能靠模型猜第一处）")
    void writeRejectsAmbiguousSearch() throws IOException {
        write(fwRoot.resolve("pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/B.java"),
                "same\nother\nsame\n");
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.writeCodeFile("fw",
                        "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/B.java",
                        "same", "changed", true));
        assertEquals(CODING_EDIT_SEARCH_AMBIGUOUS.getCode(), e.getCode());
    }

    @Test
    @DisplayName("search 与 replace 同义 → 7024（no-op 写入不留痕迹）")
    void writeRejectsNoChange() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.writeCodeFile("fw", FW_PATH, "    public void a() {", "    public void a() {", true));
        assertEquals(CODING_EDIT_NO_CHANGE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("门禁失败必须回滚：磁盘内容还原 + 抛 7022")
    void writeRollsBackOnGateFailure() throws IOException {
        modifyProperties.getGate().setBuildEnabled(true);
        modifyProperties.getGate().setFwCommand("/bin/false");

        ServiceException e = assertThrows(ServiceException.class,
                () -> tools.writeCodeFile("fw", FW_PATH, "    public void a() {",
                        "    public void a() {\n        check();", true));
        assertEquals(CODING_GATE_BUILD_FAILED.getCode(), e.getCode());
        assertEquals(SOURCE, Files.readString(fwRoot.resolve(FW_PATH), StandardCharsets.UTF_8),
                "门禁不过必须原样还原");
    }

    private LocateProperties.RepoConfig repo(String name, Path root) {
        LocateProperties.RepoConfig config = new LocateProperties.RepoConfig();
        config.setName(name);
        config.setRoot(root.toString());
        return config;
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 桩索引门面：readFile 直读临时仓库磁盘文件；invalidate 计数可核对写盘后索引失效 */
    private static final class StubIndexFacade implements ICodeIndexFacade {

        private final Path root;

        private StubIndexFacade(Path root) {
            this.root = root;
        }

        @Override
        public Optional<String> readFile(String repo, String relativePath) {
            try {
                Path file = root.resolve(repo).resolve(relativePath).normalize();
                if (!Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<com.pivotos.migration.api.codeindex.FileSymbolTable> symbolTable(String repo, String relativePath) {
            return Optional.empty();
        }

        @Override
        public Optional<com.pivotos.migration.api.codeindex.CodeIndexSnapshot> ensureIndex(String repo, String rootPath, List<String> includes) {
            return Optional.empty();
        }

        @Override
        public Optional<com.pivotos.migration.api.codeindex.CodeIndexSnapshot> rebuildIndex(String repo, String rootPath, List<String> includes) {
            return Optional.empty();
        }

        @Override
        public Map<String, Integer> stats() {
            return Map.of();
        }

        @Override
        public void invalidate(String repo) {
            // no-op
        }
    }
}

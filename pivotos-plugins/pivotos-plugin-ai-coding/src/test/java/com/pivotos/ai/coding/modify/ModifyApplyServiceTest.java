package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_GATE_BUILD_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 修改型落盘测试（临时 git 仓库，不碰真实工程）。
 *
 * <p>三条必须在此锁死：
 * <ul>
 *   <li>白名单外的落点一律 7009（修改型不能成为任意文件写入口）；</li>
 *   <li><b>门禁失败必须回滚</b>——绝不留编译不过的代码在工程里；</li>
 *   <li>评审期间文件被改过导致 search 失效 → 7019 拒绝，不静默写坏文件。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("ModifyApplyService 测试")
class ModifyApplyServiceTest {

    private static final String PATH =
            "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java";
    private static final String OUT_OF_WHITELIST_PATH =
            "pivotos-plugins/pivotos-plugin-message/src/test/java/com/pivotos/message/FooTest.java";
    private static final String SOURCE = "public class TemplateServiceImpl {\n    public void a() {\n    }\n}\n";

    @TempDir
    Path tempDir;

    private ModifyProperties modifyProperties;
    private LocateProperties locateProperties;
    private ModifyGate gate;
    private StubIndexFacade facade;
    private ObjectMapper mapper;
    private ModifyApplyService service;

    @BeforeEach
    void setUp() throws IOException {
        Path file = tempDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

        modifyProperties = new ModifyProperties();
        modifyProperties.setEnabled(true);
        modifyProperties.getGate().setBuildEnabled(false); // 默认跳过真实编译，回滚用例单独开启

        locateProperties = new LocateProperties();
        LocateProperties.RepoConfig repo = new LocateProperties.RepoConfig();
        repo.setName("fw");
        repo.setRoot(tempDir.toString());
        locateProperties.setRepos(new ArrayList<>(List.of(repo)));

        mapper = new ObjectMapper();
        gate = new ModifyGate(modifyProperties);
        facade = new StubIndexFacade();
        service = new ModifyApplyService(modifyProperties, locateProperties, gate, facade, mapper);
    }

    @Test
    @DisplayName("正常落盘：内容按 edit 指令改写")
    void applyWritesFile() throws IOException {
        CodingSession session = sessionWith(PATH, """
                {"path":"%s","edits":[{"search":"    public void a() {","replace":"    public void a() {\\n        check();"}]}
                """.formatted(PATH));

        service.apply(session);

        String actual = Files.readString(tempDir.resolve(PATH), StandardCharsets.UTF_8);
        assertTrue(actual.contains("        check();"), "落盘内容应含新增语句：\n" + actual);
        assertTrue(actual.contains("public class TemplateServiceImpl {"));
    }

    @Test
    @DisplayName("门禁失败必须回滚：磁盘内容还原 + 抛 7022")
    void rollbackOnBuildGateFailure() throws IOException {
        // 用必然失败的命令模拟编译门禁不过
        modifyProperties.getGate().setBuildEnabled(true);
        modifyProperties.getGate().setFwCommand("/bin/false");
        CodingSession session = sessionWith(PATH, """
                {"path":"%s","edits":[{"search":"    public void a() {","replace":"    public void a() {\\n        check();"}]}
                """.formatted(PATH));

        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(session));
        assertEquals(CODING_GATE_BUILD_FAILED.getCode(), e.getCode());

        assertEquals(SOURCE, Files.readString(tempDir.resolve(PATH), StandardCharsets.UTF_8),
                "门禁不过必须原样还原，绝不留编译不过的代码");
    }

    @Test
    @DisplayName("白名单外落点 → 7009，且不写盘")
    void outOfWhitelistRejected() throws IOException {
        CodingSession session = sessionWith(OUT_OF_WHITELIST_PATH,
                "{\"path\":\"" + OUT_OF_WHITELIST_PATH + "\",\"edits\":[{\"search\":\"x\",\"replace\":\"y\"}]}");

        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(session));
        assertEquals(CODING_PATH_REJECTED.getCode(), e.getCode());
        assertEquals(SOURCE, Files.readString(tempDir.resolve(PATH), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("评审期间文件被改过导致 search 失效 → 7019，不静默写坏文件")
    void staleSearchRejected() throws IOException {
        CodingSession session = sessionWith(PATH,
                "{\"path\":\"" + PATH + "\",\"edits\":[{\"search\":\"已经不存在的方法签名()\",\"replace\":\"x\"}]}");

        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(session));
        assertEquals(com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING.getCode(),
                e.getCode());
        assertEquals(SOURCE, Files.readString(tempDir.resolve(PATH), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("能力未开启 → 7016")
    void disabled() {
        modifyProperties.setEnabled(false);
        CodingSession session = sessionWith(PATH,
                "{\"path\":\"" + PATH + "\",\"edits\":[{\"search\":\"x\",\"replace\":\"y\"}]}");
        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(session));
        assertEquals(com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_DISABLED.getCode(),
                e.getCode());
    }

    private CodingSession sessionWith(String path, String editJson) {
        CodingSession session = new CodingSession();
        session.setId(1L);
        session.setTaskType(5);
        session.setStatus(1);
        try {
            session.setLocateJson(mapper.writeValueAsString(Map.of("repo", "fw",
                    "chosen", Map.of("path", path))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        session.setEditJson(editJson);
        return session;
    }

    /** 桩索引门面：readFile 直读临时仓库磁盘文件（重放校验以磁盘当前内容为准） */
    private final class StubIndexFacade implements ICodeIndexFacade {

        @Override
        public Optional<String> readFile(String repo, String relativePath) {
            try {
                Path file = tempDir.resolve(relativePath).normalize();
                if (!file.startsWith(tempDir) || !Files.exists(file)) {
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

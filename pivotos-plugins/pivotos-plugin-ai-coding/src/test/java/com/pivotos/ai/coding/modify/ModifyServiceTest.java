package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.ai.coding.locate.CodeLocateService;
import com.pivotos.ai.coding.locate.LocateLlmClient;
import com.pivotos.ai.coding.mapper.CodingSessionMapper;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_DESC_EMPTY;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_DISABLED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 修改型编排测试（桩 LLM + 桩索引门面，门禁走真实 git，不联网、不改工程文件）。
 *
 * <p>锁死三条链路不变量：
 * <ul>
 *   <li>产物是**确定性渲染的 diff**，不是 LLM 给的文本，且能被 {@code git apply} 消费；</li>
 *   <li>落库的是待评审（status=1 / taskType=5）会话，**prepare 阶段不碰磁盘**；</li>
 *   <li>落点越出白名单、能力未开启、edit 不可校验，分别在落盘前被拦。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@DisplayName("ModifyService 测试")
class ModifyServiceTest {

    private static final String PATH =
            "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java";

    /** 白名单外的落点（src/test 不在 PLUGIN_FILE 白名单内），用于验证 7009 越界拦截 */
    private static final String OUT_OF_WHITELIST_PATH =
            "pivotos-plugins/pivotos-plugin-message/src/test/java/com/pivotos/message/FooTest.java";

    private static final String SOURCE = String.join("\n",
            "public class TemplateServiceImpl {",
            "    public void deleteTemplate(Long id) {",
            "        removeById(id);",
            "    }",
            "}",
            "");

    @TempDir
    Path tempDir;

    private ModifyProperties modifyProperties;
    private LocateProperties locateProperties;
    private StubLlm llm;
    private StubIndexFacade facade;
    private CodingSessionMapper sessionMapper;
    private ModifyService service;
    private boolean gitReady;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // 真实 git 仓库：门禁的 git apply --check 需要一个工作区
        gitReady = gitInit(tempDir);
        Path file = tempDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

        modifyProperties = new ModifyProperties();
        modifyProperties.setEnabled(true);
        modifyProperties.getGate().setBuildEnabled(false);

        locateProperties = new LocateProperties();
        locateProperties.setEnabled(true);
        LocateProperties.RepoConfig repo = new LocateProperties.RepoConfig();
        repo.setName("fw");
        repo.setRoot(tempDir.toString());
        repo.setIncludes(List.of("pivotos-plugins/**/src/main/java/**/*.java"));
        locateProperties.setRepos(new ArrayList<>(List.of(repo)));

        llm = new StubLlm();
        facade = new StubIndexFacade();
        sessionMapper = mock(CodingSessionMapper.class);
        // BaseMapper 有 insert(T) / insert(Collection<T>) 两个重载，匹配器必须显式给类型
        when(sessionMapper.insert(any(CodingSession.class))).thenReturn(1);

        CodeLocateService locateService = new CodeLocateService(
                locateProperties, facade, llm, new ObjectMapper());
        service = new ModifyService(modifyProperties, locateProperties, locateService, llm,
                facade, new ModifyGate(modifyProperties), sessionMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("全链路：定位 → edit → 确定性 diff → 待评审会话（不落盘）")
    void prepareProducesReviewSession() {
        llm.editResponse = """
                {"path":"%s","edits":[{"search":"        removeById(id);",
                 "replace":"        checkEnabled(id);\\n        removeById(id);","reason":"先校验启用状态"}]}
                """.formatted(PATH);

        CodingSessionVO vo = service.prepare("模板删除加保护：启用中的模板不允许删除", "fw", null);

        assertEquals(ModifyService.TASK_TYPE_MODIFY, vo.getTaskType());
        assertEquals(1, vo.getStatus(), "prepare 只产出待评审会话");
        assertNotNull(vo.getDiff());
        assertTrue(vo.getDiff().contains("@@ -"), "必须产出 unified diff");
        assertTrue(vo.getDiff().contains("+        checkEnabled(id);"));
        assertTrue(vo.getDiff().contains("         removeById(id);"), "上下文须逐字复制");
        assertNotNull(vo.getLocate());
        assertNotNull(vo.getEdit());
        assertNotNull(vo.getGate());
        assertEquals(Boolean.TRUE, vo.getGate().get("applyCheck"));
        if (gitReady) {
            assertEquals(Boolean.FALSE, vo.getGate().get("recountUsed"),
                    "确定性渲染的 diff 不应需要 --recount 兜底");
        }

        // 磁盘原文必须原封不动：prepare 不落盘
        assertDoesNotThrow(() -> assertEquals(SOURCE,
                Files.readString(tempDir.resolve(PATH), StandardCharsets.UTF_8)));

        ArgumentCaptor<CodingSession> captor = ArgumentCaptor.forClass(CodingSession.class);
        verify(sessionMapper).insert(captor.capture());
        CodingSession session = captor.getValue();
        assertEquals(ModifyService.TASK_TYPE_MODIFY, session.getTaskType());
        assertEquals(1, session.getStatus());
        assertNotNull(session.getDiffText());
        assertNotNull(session.getEditJson());
        assertNotNull(session.getLocateJson());
    }

    @Test
    @DisplayName("edit 的 search 段不存在 → 7019，且不落库")
    void unresolvableEditRejected() {
        llm.editResponse = "{\"path\":\"" + PATH + "\",\"edits\":[{\"search\":\"臆造的方法调用();\",\"replace\":\"x\"}]}";
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.prepare("加保护", "fw", null));
        assertEquals(CODING_EDIT_SEARCH_MISSING.getCode(), e.getCode());
        verify(sessionMapper, never()).insert(any(CodingSession.class));
    }

    @Test
    @DisplayName("能力未开启 → 7016；意图为空 → 7001")
    void guards() {
        modifyProperties.setEnabled(false);
        ServiceException disabled = assertThrows(ServiceException.class,
                () -> service.prepare("加保护", "fw", null));
        assertEquals(CODING_MODIFY_DISABLED.getCode(), disabled.getCode());

        modifyProperties.setEnabled(true);
        ServiceException empty = assertThrows(ServiceException.class,
                () -> service.prepare("  ", "fw", null));
        assertEquals(CODING_DESC_EMPTY.getCode(), empty.getCode());
    }

    @Test
    @DisplayName("落点越出白名单 → 7009（修改型不能成为任意文件写入口）")
    void outOfWhitelistRejected() {
        llm.coarseResponse = """
                {"parse":{},"candidates":[{"path":"%s","confidence":0.9}]}
                """.formatted(OUT_OF_WHITELIST_PATH);
        llm.preciseByPath.put(OUT_OF_WHITELIST_PATH,
                "{\"method\":\"foo\",\"start_line\":1,\"end_line\":2,\"applicable\":true,\"confidence\":0.9,\"reason\":\"x\"}");

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.prepare("改点东西", "fw", null));
        assertEquals(CODING_PATH_REJECTED.getCode(), e.getCode());
        verify(sessionMapper, never()).insert(any(CodingSession.class));
    }

    // ---------------- 夹具 ----------------

    private static boolean gitInit(Path dir) {
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "init", "-q");
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            return builder.start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** 桩 LLM：粗筛/精定位喂给定位链路，edit 指令喂给修改链路 */
    private static final class StubLlm implements LocateLlmClient {

        private String coarseResponse = """
                {"parse":{"domain":"message","change_type":"logic","keywords":["deleteTemplate"]},
                 "candidates":[{"path":"%s","confidence":0.9,"reason":"业务实现层"}]}
                """.formatted(PATH);
        private final Map<String, String> preciseByPath = new LinkedHashMap<>();
        private String editResponse = "{\"path\":\"" + PATH + "\",\"edits\":[]}";

        @Override
        public String call(String systemPrompt, String userPrompt, String model) {
            if (systemPrompt.contains("结构化 edit 指令")) {
                return editResponse;
            }
            if (systemPrompt.contains("代码定位助手") && !systemPrompt.contains("精确")) {
                return coarseResponse;
            }
            for (Map.Entry<String, String> entry : preciseByPath.entrySet()) {
                if (userPrompt.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return "{\"method\":\"deleteTemplate\",\"start_line\":3,\"end_line\":3,"
                    + "\"applicable\":true,\"confidence\":0.9,\"reason\":\"命中\"}";
        }
    }

    /** 桩索引门面 */
    private static final class StubIndexFacade implements ICodeIndexFacade {

        private final Map<String, String> files = new LinkedHashMap<>();

        private StubIndexFacade() {
            files.put(PATH, SOURCE);
            files.put(OUT_OF_WHITELIST_PATH, "class FooTest {\n}\n");
        }

        private CodeIndexSnapshot snapshot() {
            FileSymbolTable table = new FileSymbolTable(PATH, CodeLayer.SERVICE_IMPL,
                    "TemplateServiceImpl", "模板服务实现", "com.pivotos.message.service.impl",
                    List.of("java.util.List"),
                    List.of(new CodeSymbol("deleteTemplate", "method", "void deleteTemplate(Long id)", 2)),
                    5);
            FileSymbolTable outTable = new FileSymbolTable(OUT_OF_WHITELIST_PATH, CodeLayer.UNKNOWN,
                    "FooTest", "测试类", "com.pivotos.message", List.of(), List.of(), 2);
            List<CodeFileEntry> entries = List.of(
                    new CodeFileEntry(PATH, "pivotos-plugins/pivotos-plugin-message",
                            CodeLayer.SERVICE_IMPL, "TemplateServiceImpl", "模板服务实现",
                            List.of("deleteTemplate"), 5),
                    new CodeFileEntry(OUT_OF_WHITELIST_PATH, "pivotos-plugins/pivotos-plugin-message",
                            CodeLayer.UNKNOWN, "FooTest", "测试类", List.of("foo"), 2));
            return new CodeIndexSnapshot("fw", "/tmp/repo", entries,
                    Map.of(PATH, table, OUT_OF_WHITELIST_PATH, outTable));
        }

        @Override
        public Optional<CodeIndexSnapshot> ensureIndex(String repo, String rootPath, List<String> includes) {
            return Optional.of(snapshot());
        }

        @Override
        public Optional<CodeIndexSnapshot> rebuildIndex(String repo, String rootPath, List<String> includes) {
            return Optional.of(snapshot());
        }

        @Override
        public Optional<FileSymbolTable> symbolTable(String repo, String relativePath) {
            return Optional.ofNullable(snapshot().symbolTables().get(relativePath));
        }

        @Override
        public Optional<String> readFile(String repo, String relativePath) {
            return Optional.ofNullable(files.get(relativePath));
        }

        @Override
        public Map<String, Integer> stats() {
            return Map.of("fw", files.size());
        }

        @Override
        public void invalidate(String repo) {
            // no-op
        }
    }
}

package com.pivotos.ai.coding.locate;

import com.pivotos.ai.coding.api.dto.LocateCandidateVO;
import com.pivotos.ai.coding.api.dto.LocateRequest;
import com.pivotos.ai.coding.api.dto.LocateResultVO;
import com.pivotos.ai.coding.config.LocateProperties;
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
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两段定位服务测试（桩 LLM + 桩索引门面，不联网、不烧 token）。
 *
 * <p>覆盖一票否决点所依赖的三条确定性行为：
 * <ul>
 *   <li>臆造路径被确定性闸门丢弃（LLM 只能从索引里选，不能编路径）；</li>
 *   <li>粗筛把上层文件排第一时，分层因子 + 符号命中能把实现层翻上来（spike K2 的反向修正）；</li>
 *   <li>全部候选判为不适用时降级选中并打标，不静默返回空。</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@DisplayName("CodeLocateService 测试")
class CodeLocateServiceTest {

    private static final String IMPL_PATH =
            "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java";
    private static final String CONTROLLER_PATH =
            "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/controller/MsgTemplateController.java";

    private CodeIndexSnapshot snapshot;
    private Map<String, String> files;
    private LocateProperties properties;
    private StubLlm llm;
    private CodeLocateService service;

    @BeforeEach
    void setUp() {
        FileSymbolTable impl = new FileSymbolTable(IMPL_PATH, CodeLayer.SERVICE_IMPL, "TemplateServiceImpl",
                "消息模板服务实现", "com.pivotos.message.service.impl", List.of("java.util.List"),
                List.of(new CodeSymbol("deleteTemplate", "method", "void deleteTemplate(Long id)", 71),
                        new CodeSymbol("render", "method", "String render(String tpl)", 91)),
                120);
        FileSymbolTable controller = new FileSymbolTable(CONTROLLER_PATH, CodeLayer.CONTROLLER,
                "MsgTemplateController", "消息模板接口", "com.pivotos.message.controller", List.of(),
                List.of(new CodeSymbol("delete", "method", "void delete(Long id)", 40)), 80);

        List<CodeFileEntry> entries = List.of(
                new CodeFileEntry(IMPL_PATH, "pivotos-plugins/pivotos-plugin-message",
                        CodeLayer.SERVICE_IMPL, "TemplateServiceImpl", "消息模板服务实现",
                        List.of("deleteTemplate", "render"), 120),
                new CodeFileEntry(CONTROLLER_PATH, "pivotos-plugins/pivotos-plugin-message",
                        CodeLayer.CONTROLLER, "MsgTemplateController", "消息模板接口",
                        List.of("delete"), 80));
        snapshot = new CodeIndexSnapshot("fw", "/tmp/repo", entries,
                Map.of(IMPL_PATH, impl, CONTROLLER_PATH, controller));

        files = new LinkedHashMap<>();
        files.put(IMPL_PATH, lineNumbered(120));
        files.put(CONTROLLER_PATH, lineNumbered(80));

        properties = new LocateProperties();
        properties.setEnabled(true);
        properties.setTopN(5);
        LocateProperties.RepoConfig repo = new LocateProperties.RepoConfig();
        repo.setName("fw");
        repo.setRoot("/tmp/repo");
        repo.setIncludes(List.of("pivotos-plugins/**/src/main/java/**/*.java"));
        properties.setRepos(new ArrayList<>(List.of(repo)));

        llm = new StubLlm();
        service = new CodeLocateService(properties, new StubIndexFacade(snapshot, files), llm, new ObjectMapper());
    }

    @Test
    @DisplayName("全链路：粗筛首选 Controller 时，实现层靠分层因子翻盘胜出")
    void implementationLayerWinsEndToEnd() {
        // LLM 粗筛复刻 spike K2 的偏差：Controller 置信度最高，且混一条臆造路径
        llm.coarseResponse = """
                {"parse":{"domain":"message","change_type":"logic","keywords":["deleteTemplate","模板","删除"]},
                 "candidates":[
                   {"path":"%s","confidence":0.9,"reason":"删除入口"},
                   {"path":"pivotos-plugins/pivotos-plugin-message/src/main/java/Fake.java","confidence":0.8,"reason":"臆造路径"}]}
                """.formatted(CONTROLLER_PATH);
        llm.preciseByPath.put(CONTROLLER_PATH,
                "{\"method\":\"delete\",\"start_line\":40,\"end_line\":44,\"applicable\":true,\"confidence\":0.9,\"reason\":\"入口\"}");
        llm.preciseByPath.put(IMPL_PATH,
                "{\"method\":\"deleteTemplate\",\"start_line\":71,\"end_line\":75,\"applicable\":true,\"confidence\":0.75,\"reason\":\"业务实现\"}");

        LocateResultVO result = service.locate(request("消息模板删除加保护：启用中的模板不允许删除", null));

        assertNotNull(result.getChosen());
        assertEquals(IMPL_PATH, result.getChosen().getPath(), "实现层必须胜出（分层约定感知）");
        assertEquals("deleteTemplate", result.getChosen().getMethod());
        assertEquals(71, result.getChosen().getStartLine());
        assertFalse(Boolean.TRUE.equals(result.getFallback()));
        assertEquals(2, result.getIndexSize());
        // 臆造路径必须被确定性闸门丢弃
        assertTrue(result.getCandidates().stream().noneMatch(c -> c.getPath().contains("Fake.java")));
        // 关键词召回补位：实现层进得了候选集
        assertTrue(result.getCandidates().stream().anyMatch(c -> IMPL_PATH.equals(c.getPath())
                && "keyword".equals(c.getSource())));
    }

    @Test
    @DisplayName("全部候选判为不适用：降级选中置信度最高者并打标")
    void fallbackWhenNothingApplicable() {
        llm.coarseResponse = """
                {"parse":{"domain":"message","change_type":"logic","keywords":["deleteTemplate"]},
                 "candidates":[{"path":"%s","confidence":0.8,"reason":"x"}]}
                """.formatted(CONTROLLER_PATH);
        llm.preciseByPath.put(CONTROLLER_PATH,
                "{\"method\":\"delete\",\"start_line\":0,\"end_line\":0,\"applicable\":false,\"confidence\":0.3,\"reason\":\"只是调用方\"}");
        llm.preciseByPath.put(IMPL_PATH,
                "{\"method\":\"render\",\"start_line\":0,\"end_line\":0,\"applicable\":false,\"confidence\":0.6,\"reason\":\"无关\"}");

        LocateResultVO result = service.locate(request("无关意图", null));

        assertTrue(Boolean.TRUE.equals(result.getFallback()));
        assertNotNull(result.getChosen());
        assertEquals(IMPL_PATH, result.getChosen().getPath(), "降级时取置信度最高者");
    }

    @Test
    @DisplayName("精定位失败/输出不可解析：该候选降级为不适用，不影响其它候选")
    void preciseFailureIsolated() {
        llm.coarseResponse = """
                {"parse":{"domain":"message","change_type":"logic","keywords":["deleteTemplate"]},
                 "candidates":[{"path":"%s","confidence":0.9,"reason":"x"}]}
                """.formatted(CONTROLLER_PATH);
        llm.preciseByPath.put(CONTROLLER_PATH, "这不是 JSON");
        llm.preciseByPath.put(IMPL_PATH,
                "{\"method\":\"deleteTemplate\",\"start_line\":71,\"end_line\":75,\"applicable\":true,\"confidence\":0.9,\"reason\":\"命中\"}");

        LocateResultVO result = service.locate(request("模板删除保护", null));

        assertEquals(IMPL_PATH, result.getChosen().getPath());
        assertFalse(Boolean.TRUE.equals(result.getFallback()));
    }

    @Test
    @DisplayName("候选合并：重复路径去重，且按 maxCandidates 截断")
    void candidatesDeduplicatedAndCapped() {
        Map<String, Object> parse = Map.of("candidates", List.of(
                Map.of("path", CONTROLLER_PATH, "confidence", 0.9),
                Map.of("path", CONTROLLER_PATH, "confidence", 0.8)));
        List<LocateCandidateVO> merged = service.mergeCandidates(snapshot, parse, List.of("deleteTemplate"), 5);
        long controllerCount = merged.stream().filter(c -> CONTROLLER_PATH.equals(c.getPath())).count();
        assertEquals(1, controllerCount, "同一路径只能出现一次");
        assertEquals(2, merged.size(), "去重后应为 Controller + 关键词召回的 Impl");
    }

    @Test
    @DisplayName("能力未开启 / 仓库未登记 / 索引为空：分别抛对应错误码")
    void errorCodes() {
        LocateRequest request = request("x", null);

        properties.setEnabled(false);
        ServiceException disabled = assertThrows(ServiceException.class, () -> service.locate(request));
        assertEquals(7012, disabled.getCode());

        properties.setEnabled(true);
        LocateRequest unknownRepo = request("x", null);
        unknownRepo.setRepo("not-exist");
        ServiceException unknown = assertThrows(ServiceException.class, () -> service.locate(unknownRepo));
        assertEquals(7013, unknown.getCode());

        CodeLocateService emptyService = new CodeLocateService(properties,
                new StubIndexFacade(new CodeIndexSnapshot("fw", "/tmp/repo", List.of(), Map.of()), files),
                llm, new ObjectMapper());
        ServiceException empty = assertThrows(ServiceException.class, () -> emptyService.locate(request));
        assertEquals(7014, empty.getCode());
    }

    @Test
    @DisplayName("模型覆盖：请求级 model 透传并在结果中回显（双模型对比依赖此位）")
    void modelOverride() {
        llm.coarseResponse = """
                {"parse":{"domain":"message","change_type":"logic","keywords":["deleteTemplate"]},
                 "candidates":[{"path":"%s","confidence":0.9,"reason":"x"}]}
                """.formatted(IMPL_PATH);
        llm.preciseByPath.put(IMPL_PATH,
                "{\"method\":\"deleteTemplate\",\"start_line\":71,\"end_line\":75,\"applicable\":true,\"confidence\":0.9,\"reason\":\"命中\"}");

        LocateResultVO result = service.locate(request("模板删除保护", "qwen3.5-plus"));

        assertEquals("qwen3.5-plus", result.getModel());
        assertTrue(llm.models.stream().allMatch(m -> "qwen3.5-plus".equals(m)), "粗筛与精定位都须用同一覆盖模型");
    }

    @Test
    @DisplayName("精定位喂入：含分层标记、imports 与符号表（抑制文件外符号幻觉）")
    void feedCarriesAntiHallucinationContext() {
        LocateCandidateVO candidate = new LocateCandidateVO();
        candidate.setPath(IMPL_PATH);
        candidate.setLayer(CodeLayer.SERVICE_IMPL.name());
        FileSymbolTable table = snapshot.symbolTables().get(IMPL_PATH);
        String feed = service.renderFeed(candidate, table, files.get(IMPL_PATH));

        assertTrue(feed.contains("分层：SERVICE_IMPL"));
        assertTrue(feed.contains("java.util.List"), "必须喂 imports");
        assertTrue(feed.contains("deleteTemplate"), "必须喂符号表");
        assertTrue(feed.contains("1: "), "必须带行号全文");
    }

    // ---------------- 夹具 ----------------

    private LocateRequest request(String intent, String model) {
        LocateRequest request = new LocateRequest();
        request.setIntent(intent);
        request.setRepo("fw");
        request.setModel(model);
        return request;
    }

    private static String lineNumbered(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append(": line ").append(i).append('\n');
        }
        return sb.toString();
    }

    /** 桩 LLM：按 system 提示词区分粗筛/精定位，精定位按喂入里的路径分派响应 */
    private static final class StubLlm implements LocateLlmClient {

        private String coarseResponse = "{\"parse\":{},\"candidates\":[]}";
        private final Map<String, String> preciseByPath = new LinkedHashMap<>();
        private final List<String> models = new ArrayList<>();

        @Override
        public String call(String systemPrompt, String userPrompt, String model) {
            models.add(model);
            if (systemPrompt.contains("代码定位助手") && !systemPrompt.contains("精确")) {
                return coarseResponse;
            }
            for (Map.Entry<String, String> entry : preciseByPath.entrySet()) {
                if (userPrompt.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return "{\"method\":\"\",\"start_line\":0,\"end_line\":0,\"applicable\":false,\"confidence\":0.0,\"reason\":\"未命中桩\"}";
        }
    }

    /** 桩索引门面 */
    private record StubIndexFacade(CodeIndexSnapshot snapshot, Map<String, String> files) implements ICodeIndexFacade {

        @Override
        public Optional<CodeIndexSnapshot> ensureIndex(String repo, String rootPath, List<String> includes) {
            return Optional.of(snapshot);
        }

        @Override
        public Optional<CodeIndexSnapshot> rebuildIndex(String repo, String rootPath, List<String> includes) {
            return Optional.of(snapshot);
        }

        @Override
        public Optional<FileSymbolTable> symbolTable(String repo, String relativePath) {
            return Optional.ofNullable(snapshot.symbolTables().get(relativePath));
        }

        @Override
        public Optional<String> readFile(String repo, String relativePath) {
            return Optional.ofNullable(files.get(relativePath));
        }

        @Override
        public Map<String, Integer> stats() {
            return Map.of(snapshot.repo(), snapshot.size());
        }

        @Override
        public void invalidate(String repo) {
            // no-op
        }
    }
}

package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.api.dto.LocateRequest;
import com.pivotos.ai.coding.api.dto.LocateResultVO;
import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.ai.coding.locate.CodeLocateService;
import com.pivotos.ai.coding.locate.LocateLlmClient;
import com.pivotos.ai.coding.mapper.CodingSessionMapper;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_DESC_EMPTY;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_DIFF_CHECK_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_NO_CHANGE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_PARSE_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_GENERATE_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_DISABLED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_FILE_UNREADABLE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;

/**
 * 修改型任务编排（A4-2 / S111）：定位 → edit 生成 → 存在性校验 → 确定性 diff 渲染 → 门禁 → 待评审会话。
 *
 * <p>对应 15 号文档架构口径第 ③④⑤ 步。**LLM 只产出结构化 edit 指令，diff 由
 * {@link DiffRenderer} 确定性渲染**，LLM 全程不碰 diff 格式（spike K1 的两种死法同时归零）。
 *
 * <p>产物不落盘，只入库待评审（status=1）；落盘在既有 {@code POST /ai-coding/session/{id}/apply}
 * 链路上按 taskType=5 分派，并强制跑编译门禁（{@link ModifyApplyService}）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@Service
public class ModifyService {

    private static final Logger log = LoggerFactory.getLogger(ModifyService.class);

    /** 修改型任务类型（1=单表CRUD 2=Plugin骨架 3=主子表 4=树表 5=修改型） */
    public static final int TASK_TYPE_MODIFY = 5;

    private static final String EDIT_SYSTEM_PROMPT = """
            你是 PivotOS 仓库的资深开发。根据改动意图，对给定文件产出**结构化 edit 指令**（不是 diff）。

            【输出】只输出一个 ```json 块，schema：
            {"path":"<仓库相对路径>","edits":[{"search":"原文逐字片段","replace":"替换为","occurrence":0,"append":false,"reason":"一句话理由"}]}

            【硬约束】
            1. search 必须是文件中**逐字存在**的片段（含缩进，可多行），不许改写、省略或臆造；
               若同一片段出现多次，按 occurrence 指定第几次（1-based）。
            2. 追加到文件末尾请用 {"append":true,"replace":"..."}，不要用越界行号。
            3. 只能使用文件内已有符号与下方 imports / 符号表中的符号，**严禁臆造文件外的方法、常量、枚举**
               （这是本项目既往失败的首要原因）。
            4. 不要输出 unified diff，不要输出任何解释文字。
            """;

    private final ModifyProperties properties;
    private final LocateProperties locateProperties;
    private final CodeLocateService locateService;
    private final LocateLlmClient llmClient;
    private final ICodeIndexFacade indexFacade;
    private final ModifyGate gate;
    private final CodingSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public ModifyService(ModifyProperties properties,
                         LocateProperties locateProperties,
                         CodeLocateService locateService,
                         LocateLlmClient llmClient,
                         ICodeIndexFacade indexFacade,
                         ModifyGate gate,
                         CodingSessionMapper sessionMapper,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.locateProperties = locateProperties;
        this.locateService = locateService;
        this.llmClient = llmClient;
        this.indexFacade = indexFacade;
        this.gate = gate;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成修改型会话（不落盘，status=1 待评审）。
     *
     * @param description 自然语言改动意图
     * @param repo        目标仓库逻辑名（fw / ui）；留空取首个登记仓库
     * @param model       模型覆盖（可空）
     * @return 会话 VO（含 locate / edit / diff / gate 四段产物）
     */
    public CodingSessionVO prepare(String description, String repo, String model) {
        if (description == null || description.isBlank()) {
            throw new ServiceException(CODING_DESC_EMPTY);
        }
        if (!properties.isEnabled()) {
            throw new ServiceException(CODING_MODIFY_DISABLED);
        }
        String targetRepo = (repo == null || repo.isBlank())
                ? locateProperties.getRepos().isEmpty() ? null : locateProperties.getRepos().get(0).getName()
                : repo;
        if (targetRepo == null) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }

        // ① 定位（A4-1 两段定位产出唯一落点）
        LocateRequest locateRequest = new LocateRequest();
        locateRequest.setIntent(description);
        locateRequest.setRepo(targetRepo);
        locateRequest.setModel(model);
        LocateResultVO locate = locateService.locate(locateRequest);
        String relativePath = locate.getChosen() == null ? null : locate.getChosen().getPath();
        if (relativePath == null || relativePath.isBlank()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        // 白名单按「仓内路径 + 仓库根」判定（定位产出是仓内相对路径，见 CodingPathWhitelist.allowsInRepo）
        LocateProperties.RepoConfig repoConfig = locateProperties.repo(targetRepo);
        if (repoConfig == null || repoConfig.getRoot() == null || repoConfig.getRoot().isBlank()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        Path repoRoot = Path.of(repoConfig.getRoot());
        if (!CodingPathWhitelist.allowsInRepo(repoRoot, relativePath)) {
            log.warn("[AI Coding] Modify target out of whitelist: repo={}, path={}", targetRepo, relativePath);
            throw new ServiceException(CODING_PATH_REJECTED);
        }

        // ② 读原文 + 符号表（喂给 LLM 的是「文件内真实符号」，对冲文件外幻觉）
        Optional<String> originalOpt = indexFacade.readFile(targetRepo, relativePath);
        if (originalOpt.isEmpty()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        String original = originalOpt.get();
        Optional<FileSymbolTable> tableOpt = indexFacade.symbolTable(targetRepo, relativePath);

        // ③ LLM 产出结构化 edit 指令
        String raw = llmClient.call(EDIT_SYSTEM_PROMPT,
                renderEditPrompt(description, relativePath, locate, original, tableOpt.orElse(null)), model);
        EditInstruction instruction = EditInstructionParser.parse(raw, relativePath, objectMapper);
        if (instruction.blocks().isEmpty()) {
            log.warn("[AI Coding] Edit blocks empty: path={}, raw={}", relativePath, EditInstructionParser.excerpt(raw));
            throw new ServiceException(CODING_EDIT_PARSE_FAILED);
        }

        // ④ 存在性闸门 + 应用（edit 指令 → 改后文本）
        String modified;
        try {
            modified = instruction.apply(original);
        } catch (ServiceException e) {
            log.warn("[AI Coding] Edit gate rejected: path={}, code={}, raw={}",
                    relativePath, e.getCode(), EditInstructionParser.excerpt(raw));
            throw e;
        }
        if (original.equals(modified)) {
            log.warn("[AI Coding] Edit produced no change: path={}, blocks={}", relativePath,
                    instruction.blocks().size());
            throw new ServiceException(CODING_EDIT_NO_CHANGE);
        }

        // ⑤ 确定性渲染 diff
        String diff = DiffRenderer.render(relativePath, original, modified);

        // ⑥ 可应用性门禁（git apply --check，--recount 兜底）
        ModifyGate.ApplyCheck applyCheck = gate.applyCheck(diff, repoRoot);
        if (!applyCheck.ok()) {
            log.warn("[AI Coding] Diff check failed: path={}, msg={}", relativePath, applyCheck.message());
            throw new ServiceException(CODING_DIFF_CHECK_FAILED);
        }

        // ⑦ 落会话（待评审）
        CodingSession session = new CodingSession();
        session.setDescription(description);
        session.setStatus(1);
        session.setTaskType(TASK_TYPE_MODIFY);
        session.setModuleName(targetRepo);
        session.setFunctionName(locate.getChosen() == null ? null : locate.getChosen().getMethod());
        session.setCreateTime(LocalDateTime.now());
        try {
            session.setLocateJson(objectMapper.writeValueAsString(locate));
            session.setEditJson(objectMapper.writeValueAsString(instruction));
            session.setGateJson(objectMapper.writeValueAsString(gateMap(applyCheck)));
            session.setGeneratedFilesJson(objectMapper.writeValueAsString(Map.of(relativePath, modified)));
        } catch (Exception e) {
            log.error("[AI Coding] Serialize modify artifacts failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
        session.setDiffText(diff);
        sessionMapper.insert(session);

        log.info("[AI Coding] Modify prepared: session={}, path={}, blocks={}",
                session.getId(), relativePath, instruction.blocks().size());
        return toVO(session, locate, instruction, diff, applyCheck);
    }

    /**
     * 改后文本渲染（评审/落盘共用口径：以磁盘当前内容重放 edit）。
     *
     * @param original 当前文件内容
     * @param editJson 结构化 edit 指令 JSON
     * @return 改后文本
     */
    public String renderModified(String original, String editJson, String fallbackPath) {
        EditInstruction instruction = EditInstructionParser.parse(editJson, fallbackPath, objectMapper);
        return instruction.apply(original);
    }

    // ---------------- 私有 ----------------

    private String renderEditPrompt(String description, String relativePath, LocateResultVO locate,
                                    String original, FileSymbolTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("仓库相对路径：").append(relativePath).append('\n');
        if (locate.getChosen() != null) {
            sb.append("推荐落点：").append(locate.getChosen().getMethod())
                    .append("（第 ").append(locate.getChosen().getStartLine())
                    .append('-').append(locate.getChosen().getEndLine()).append(" 行附近）\n");
        }
        if (table != null) {
            sb.append("分层：").append(table.layer() == null ? "UNKNOWN" : table.layer()).append('\n');
            if (table.imports() != null && !table.imports().isEmpty()) {
                sb.append("imports：").append(String.join(", ", table.imports())).append('\n');
            }
            if (table.symbols() != null && !table.symbols().isEmpty()) {
                sb.append("文件内符号（可引用）：");
                sb.append(table.symbols().stream()
                        .limit(60)
                        .map(s -> s.kind() + " " + s.name() + "(@" + s.line() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse(""));
                sb.append('\n');
            }
        }
        sb.append("文件全文（行号: 内容）：\n").append(numbered(original)).append('\n');
        sb.append("改动意图：").append(description);
        return sb.toString();
    }

    private static String numbered(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Object> gateMap(ModifyGate.ApplyCheck check) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("applyCheck", check.ok());
        map.put("recountUsed", check.recountUsed());
        map.put("message", check.message());
        return map;
    }

    private CodingSessionVO toVO(CodingSession session, LocateResultVO locate,
                                 EditInstruction instruction, String diff, ModifyGate.ApplyCheck check) {
        Map<String, Object> locateMap;
        Map<String, Object> editMap;
        try {
            locateMap = objectMapper.convertValue(locate,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            editMap = objectMapper.convertValue(instruction,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            locateMap = Map.of();
            editMap = Map.of();
        }
        return CodingSessionVO.builder()
                .id(session.getId())
                .description(session.getDescription())
                .moduleName(session.getModuleName())
                .functionName(session.getFunctionName())
                .status(session.getStatus())
                .taskType(session.getTaskType())
                .diff(diff)
                .locate(locateMap)
                .edit(editMap)
                .gate(gateMap(check))
                .generatedFiles(Map.of(instruction.path(), ""))
                .build();
    }
}

package com.pivotos.ai.coding.service.impl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.ai.coding.mapper.CodingSessionMapper;
import com.pivotos.ai.coding.service.ArtifactLinter;
import com.pivotos.ai.coding.service.AssemblyPatcher;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.ai.coding.service.CrudApplyService;
import com.pivotos.ai.coding.service.ErrorCodeSegmentAllocator;
import com.pivotos.ai.coding.service.IntentParseService;
import com.pivotos.ai.coding.service.SubIntentValidator;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.generator.service.IGeneratorFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.nio.file.Path;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.*;

/**
 * AI Coding service implementation.
 * <p>
 * Flow: natural language -> LLM intent parse -> generator code preview -> staging review -> apply to project
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Service
public class CodingServiceImpl implements CodingService {

    private static final Logger log = LoggerFactory.getLogger(CodingServiceImpl.class);

    private final IntentParseService intentParseService;
    private final IGeneratorFacade generatorFacade;
    private final CodingSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final ErrorCodeSegmentAllocator segmentAllocator;
    private final ArtifactLinter artifactLinter;
    private final AssemblyPatcher assemblyPatcher;
    private final CrudApplyService crudApplyService;
    private final SubIntentValidator subIntentValidator;

    /** 骨架任务类型 */
    private static final int TASK_TYPE_CRUD = 1;
    private static final int TASK_TYPE_PLUGIN = 2;
    /** 主子表任务类型（S52 / 2.4-F5） */
    private static final int TASK_TYPE_SUB = 3;

    /** 保留插件名（与既有模块/组件冲突） */
    private static final java.util.Set<String> RESERVED_PLUGIN_NAMES = java.util.Set.of(
            "system", "message", "file", "ai", "generator", "server", "common", "starter");

    private static final java.util.regex.Pattern PLUGIN_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9]{1,15}$");

    public CodingServiceImpl(IntentParseService intentParseService,
                             IGeneratorFacade generatorFacade,
                             CodingSessionMapper sessionMapper,
                             ObjectMapper objectMapper,
                             ErrorCodeSegmentAllocator segmentAllocator,
                             ArtifactLinter artifactLinter,
                             AssemblyPatcher assemblyPatcher,
                             CrudApplyService crudApplyService,
                             SubIntentValidator subIntentValidator) {
        this.intentParseService = intentParseService;
        this.generatorFacade = generatorFacade;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
        this.segmentAllocator = segmentAllocator;
        this.artifactLinter = artifactLinter;
        this.assemblyPatcher = assemblyPatcher;
        this.crudApplyService = crudApplyService;
        this.subIntentValidator = subIntentValidator;
    }

    @Override
    @Transactional
    public CodingSessionVO parseAndGenerate(String description) {
        if (description == null || description.isBlank()) {
            throw new ServiceException(CODING_DESC_EMPTY);
        }

        // Step 1: LLM intent parse
        Map<String, Object> intent;
        try {
            intent = intentParseService.parse(description);
        } catch (Exception e) {
            log.error("[AI Coding] Intent parse failed: description={}", description, e);
            throw new ServiceException(CODING_INTENT_PARSE_FAILED);
        }

        String moduleName = (String) intent.get("moduleName");
        String functionName = (String) intent.get("functionName");
        String tableName = (String) intent.get("tableName");
        String businessName = (String) intent.get("businessName");
        String tableComment = (String) intent.get("tableComment");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) intent.get("columns");

        if (moduleName == null || tableName == null || columns == null || columns.isEmpty()) {
            throw new ServiceException(CODING_INTENT_PARSE_FAILED);
        }

        log.info("[AI Coding] Parsed: module={}, table={}, function={}, columns={}",
                moduleName, tableName, functionName, columns.size());

        // Step 2: Import table to generator and configure columns
        Long tableId = importToGenerator(moduleName, tableName, tableComment, functionName, businessName, columns);

        // Step 3: Generate code preview
        Map<String, String> generatedFiles;
        try {
            generatedFiles = generatorFacade.previewCode(tableId);
        } catch (Exception e) {
            log.error("[AI Coding] Code generation failed: tableId={}", tableId, e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // Step 4: Save session
        CodingSession session = new CodingSession();
        session.setDescription(description);
        session.setModuleName(moduleName);
        session.setTableName(tableName);
        session.setFunctionName(functionName);
        session.setBusinessName(businessName);
        session.setStatus(1); // pending review
        try {
            session.setGeneratedFilesJson(objectMapper.writeValueAsString(generatedFiles));
        } catch (Exception e) {
            log.error("[AI Coding] Serialize generated files failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
        sessionMapper.insert(session);

        return toVO(session, generatedFiles);
    }

    @Override
    @Transactional
    public void applyToProject(Long userId, Long sessionId) {
        CodingSession session = requireOwned(userId, sessionId);
        if (Integer.valueOf(TASK_TYPE_PLUGIN).equals(session.getTaskType())) {
            applyPluginSkeleton(session);
            return;
        }
        // S43：CRUD 产物走 CrudApplyService（落盘路径修正 + Flyway 版本分配 + pages.json 注册）
        crudApplyService.apply(session);
        session.setStatus(2); // applied
        sessionMapper.updateById(session);
    }

    // ==================== 主子表（S52 / 2.4-F5） ====================

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public CodingSessionVO parseAndGenerateSub(String description) {
        if (description == null || description.isBlank()) {
            throw new ServiceException(CODING_DESC_EMPTY);
        }

        // Step 1: LLM 主子意图解析
        Map<String, Object> intent;
        try {
            intent = intentParseService.parseSubIntent(description);
        } catch (Exception e) {
            log.error("[AI Coding] Sub intent parse failed: description={}", description, e);
            throw new ServiceException(CODING_INTENT_PARSE_FAILED);
        }

        // Step 2: 确定性校验（不信 LLM：标识符/关系闭合/审计列/fk 探测降级，7011 拦截）
        subIntentValidator.validate(intent);

        String moduleName = (String) intent.get("moduleName");
        String functionName = (String) intent.get("functionName");
        Map<String, Object> main = (Map<String, Object>) intent.get("main");
        Map<String, Object> sub = (Map<String, Object>) intent.get("sub");
        Map<String, Object> relation = (Map<String, Object>) intent.get("relation");
        String mainTable = (String) main.get("tableName");
        String subTable = (String) sub.get("tableName");
        String subFkName = (String) relation.get("subFkName");
        log.info("[AI Coding] Sub parsed: main={}, sub={}, fk={}", mainTable, subTable, subFkName);

        // Step 3: 先导子表再导主表，随后强制写入主子配置（importTable 幂等可能返回旧记录）
        Long mainTableId;
        try {
            generatorFacade.importTable(subTable, moduleName,
                    (String) sub.get("tableComment"), (String) sub.get("businessName"),
                    (String) sub.get("tableComment"), (List<Map<String, Object>>) sub.get("columns"));
            mainTableId = generatorFacade.importTable(mainTable, moduleName, functionName,
                    (String) main.get("businessName"), (String) main.get("tableComment"),
                    (List<Map<String, Object>>) main.get("columns"));
            generatorFacade.configureSubTable(mainTable, subTable, subFkName);
        } catch (Exception e) {
            log.error("[AI Coding] Sub table import failed: main={}, sub={}", mainTable, subTable, e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // Step 4: 主子全套产物预览（S51 模板族：主 21 文件含子四件套 + 双表 DDL + 菜单）
        Map<String, String> generatedFiles;
        try {
            generatedFiles = generatorFacade.previewCode(mainTableId);
        } catch (Exception e) {
            log.error("[AI Coding] Sub code generation failed: tableId={}", mainTableId, e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // Step 5: 红线 lint（报告随会话留存，apply 时 CrudApplyService 强校验）
        List<String> violations = artifactLinter.lint(generatedFiles);
        if (!violations.isEmpty()) {
            log.warn("[AI Coding] Sub artifacts lint violations: {}", violations);
        }

        // Step 6: 暂存会话（tableName=主表；sub 元数据入 extraJson）
        CodingSession session = new CodingSession();
        session.setDescription(description);
        session.setModuleName(moduleName);
        session.setTableName(mainTable);
        session.setFunctionName(functionName);
        session.setBusinessName((String) main.get("businessName"));
        session.setStatus(1);
        session.setTaskType(TASK_TYPE_SUB);
        try {
            session.setGeneratedFilesJson(objectMapper.writeValueAsString(generatedFiles));
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("subTableName", subTable);
            extra.put("subFkName", subFkName);
            extra.put("lintReport", violations);
            session.setExtraJson(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            log.error("[AI Coding] Serialize sub session failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
        sessionMapper.insert(session);

        return toVO(session, generatedFiles);
    }

    // ==================== Plugin 骨架（S42 / 2.2-F12） ====================
    @Override
    @Transactional
    public CodingSessionVO parseAndGeneratePlugin(String description) {
        if (description == null || description.isBlank()) {
            throw new ServiceException(CODING_DESC_EMPTY);
        }

        // Step 1: LLM 骨架意图（只推断命名类参数）
        Map<String, Object> intent;
        try {
            intent = intentParseService.parsePluginIntent(description);
        } catch (Exception e) {
            log.error("[AI Coding] Plugin intent parse failed: description={}", description, e);
            throw new ServiceException(CODING_INTENT_PARSE_FAILED);
        }
        String pluginName = asString(intent.get("pluginName"));
        String displayName = asString(intent.get("displayName"));
        String tablePrefix = asString(intent.get("tablePrefix"));
        String moduleDesc = asString(intent.get("moduleDesc"));

        // Step 2: 确定性校验/分配（LLM 输出不可信）
        if (pluginName == null || !PLUGIN_NAME_PATTERN.matcher(pluginName).matches()
                || RESERVED_PLUGIN_NAMES.contains(pluginName)) {
            log.warn("[AI Coding] Invalid plugin name from LLM: {}", pluginName);
            throw new ServiceException(CODING_PLUGIN_NAME_INVALID);
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = pluginName + " 管理";
        }
        if (tablePrefix == null || !tablePrefix.matches("^[a-z][a-z0-9]*_$")) {
            tablePrefix = pluginName + "_";
        }
        if (moduleDesc == null || moduleDesc.isBlank()) {
            moduleDesc = displayName + "（AI Coding 骨架生成）";
        }
        // 目录冲突前置检查（评审前拦一次，apply 时再拦一次防竞态）
        Path frameworkRoot = assemblyPatcher.resolveFrameworkRoot();
        if (java.nio.file.Files.exists(frameworkRoot.resolve("pivotos-plugins/pivotos-plugin-" + pluginName))) {
            throw new ServiceException(CODING_PLUGIN_EXISTS);
        }
        int errorCodeBase = segmentAllocator.allocateFreeSegment() * 1000;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pluginName", pluginName);
        params.put("className", Character.toUpperCase(pluginName.charAt(0)) + pluginName.substring(1));
        params.put("displayName", displayName);
        params.put("tablePrefix", tablePrefix);
        params.put("moduleDesc", moduleDesc);
        params.put("errorCodeBase", errorCodeBase);
        log.info("[AI Coding] Plugin skeleton parsed: name={}, display={}, codeBase={}",
                pluginName, displayName, errorCodeBase);

        // Step 3: 生成器骨架渲染
        Map<String, String> generatedFiles;
        try {
            generatedFiles = generatorFacade.previewPluginSkeleton(params);
        } catch (Exception e) {
            log.error("[AI Coding] Plugin skeleton render failed: pluginName={}", pluginName, e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // Step 4: 红线 lint（报告随会话留存，apply 时强校验）
        List<String> violations = artifactLinter.lint(generatedFiles);
        if (!violations.isEmpty()) {
            log.warn("[AI Coding] Plugin skeleton lint violations: {}", violations);
        }

        // Step 5: 暂存会话
        CodingSession session = new CodingSession();
        session.setDescription(description);
        session.setModuleName(pluginName);
        session.setFunctionName(displayName);
        session.setStatus(1);
        session.setTaskType(TASK_TYPE_PLUGIN);
        try {
            session.setGeneratedFilesJson(objectMapper.writeValueAsString(generatedFiles));
            Map<String, Object> extra = new LinkedHashMap<>(params);
            extra.put("lintReport", violations);
            session.setExtraJson(objectMapper.writeValueAsString(extra));
        } catch (Exception e) {
            log.error("[AI Coding] Serialize plugin skeleton failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
        sessionMapper.insert(session);

        return toVO(session, generatedFiles);
    }

    /** 骨架应用：lint 强校验 → 路径白名单 → 落盘 → 装配三处登记 */
    private void applyPluginSkeleton(CodingSession session) {
        Map<String, String> files = parseGeneratedFiles(session.getGeneratedFilesJson());
        Map<String, Object> extra = parseExtra(session.getExtraJson());
        String pluginName = asString(extra.get("pluginName"));
        String displayName = asString(extra.get("displayName"));
        if (pluginName == null) {
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // ① 红线 lint 门禁（落盘前拦截，D4）
        List<String> violations = artifactLinter.lint(files);
        if (!violations.isEmpty()) {
            log.warn("[AI Coding] Apply rejected by lint: session={}, violations={}", session.getId(), violations);
            throw new ServiceException(CODING_LINT_FAILED);
        }

        // ② 路径白名单：仅 pivotos-plugins/pivotos-plugin-{name}(-api)/**
        java.util.regex.Pattern allowed = java.util.regex.Pattern.compile(
                "^pivotos-plugins/pivotos-plugin-" + java.util.regex.Pattern.quote(pluginName)
                        + "(-api)?/.+");
        for (String path : files.keySet()) {
            if (path.contains("..") || !allowed.matcher(path).matches()) {
                log.warn("[AI Coding] Path rejected: {}", path);
                throw new ServiceException(CODING_PATH_REJECTED);
            }
        }

        // ③ 目录冲突复查（apply 时防竞态/重复应用）
        Path frameworkRoot = assemblyPatcher.resolveFrameworkRoot();
        Path pluginDir = frameworkRoot.resolve("pivotos-plugins/pivotos-plugin-" + pluginName);
        if (java.nio.file.Files.exists(pluginDir)) {
            throw new ServiceException(CODING_PLUGIN_EXISTS);
        }

        // ④ 落盘
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path target = frameworkRoot.resolve(entry.getKey()).normalize();
                if (!target.startsWith(frameworkRoot)) {
                    throw new ServiceException(CODING_PATH_REJECTED);
                }
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            log.error("[AI Coding] Write skeleton files failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // ⑤ 装配登记（pom modules / admin-server 依赖 / scanBasePackages，幂等）
        assemblyPatcher.patch(frameworkRoot, pluginName, displayName);

        session.setStatus(2);
        sessionMapper.updateById(session);
        log.info("[AI Coding] Plugin skeleton applied: session={}, plugin={}", session.getId(), pluginName);
    }

    private Map<String, Object> parseExtra(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("[AI Coding] Failed to parse extra JSON", e);
            return Map.of();
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Override
    public PageResult<CodingSessionVO> pageSessions(Long userId, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<CodingSession> wrapper = new LambdaQueryWrapper<CodingSession>()
                // 行级隔离：仅本人创建的会话（create_by = 当前登录用户）
                .eq(CodingSession::getCreateBy, userId)
                // 列表视图不取大字段 generatedFilesJson
                .select(CodingSession.class, f -> !"generated_files_json".equals(f.getColumn()))
                .orderByDesc(CodingSession::getCreateTime);
        Page<CodingSession> page = sessionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(
                page.getRecords().stream().map(s -> toVO(s, null)).toList(),
                page.getTotal(), pageNum, pageSize);
    }

    @Override
    public CodingSessionVO getSession(Long userId, Long sessionId) {
        CodingSession session = requireOwned(userId, sessionId);
        Map<String, String> files = parseGeneratedFiles(session.getGeneratedFilesJson());
        return toVO(session, files);
    }

    /** 归属校验：不存在或非本人一律 7004（不泄露资源存在性），对齐 AI 对话 requireOwned 范式 */
    private CodingSession requireOwned(Long userId, Long sessionId) {
        CodingSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getCreateBy() == null || !session.getCreateBy().equals(userId)) {
            throw new ServiceException(CODING_SESSION_NOT_FOUND);
        }
        return session;
    }

    // ======================== Private helpers ========================

    private Long importToGenerator(String moduleName, String tableName, String tableComment,
                                   String functionName, String businessName, List<Map<String, Object>> columns) {
        // Use IGeneratorFacade to import table definition and configure columns
        try {
            return generatorFacade.importTable(tableName, moduleName, functionName, businessName, tableComment, columns);
        } catch (Exception e) {
            log.error("[AI Coding] Table import failed: tableName={}", tableName, e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
    }

    private Map<String, String> parseGeneratedFiles(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("[AI Coding] Failed to parse generated files JSON", e);
            return Map.of();
        }
    }

    private CodingSessionVO toVO(CodingSession session, Map<String, String> files) {
        return CodingSessionVO.builder()
                .id(session.getId())
                .description(session.getDescription())
                .moduleName(session.getModuleName())
                .tableName(session.getTableName())
                .functionName(session.getFunctionName())
                .businessName(session.getBusinessName())
                .status(session.getStatus())
                .taskType(session.getTaskType())
                .extra(parseExtra(session.getExtraJson()))
                .generatedFiles(files)
                .createBy(session.getCreateBy())
                .createTime(session.getCreateTime())
                .build();
    }
}

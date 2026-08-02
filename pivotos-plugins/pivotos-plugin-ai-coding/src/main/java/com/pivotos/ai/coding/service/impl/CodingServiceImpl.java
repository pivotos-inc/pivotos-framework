package com.pivotos.ai.coding.service.impl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.ai.coding.mapper.CodingSessionMapper;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.ai.coding.service.IntentParseService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.generator.service.IGeneratorFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    public CodingServiceImpl(IntentParseService intentParseService,
                             IGeneratorFacade generatorFacade,
                             CodingSessionMapper sessionMapper,
                             ObjectMapper objectMapper) {
        this.intentParseService = intentParseService;
        this.generatorFacade = generatorFacade;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
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
    public void applyToProject(Long sessionId) {
        CodingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ServiceException(CODING_SESSION_NOT_FOUND);
        }
        // Generate directly to project
        generatorFacade.generateToProject(session.getTableName());
        session.setStatus(2); // applied
        sessionMapper.updateById(session);
    }

    @Override
    public CodingSessionVO getSession(Long sessionId) {
        CodingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ServiceException(CODING_SESSION_NOT_FOUND);
        }
        Map<String, String> files = parseGeneratedFiles(session.getGeneratedFilesJson());
        return toVO(session, files);
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
                .generatedFiles(files)
                .createBy(session.getCreateBy())
                .createTime(session.getCreateTime())
                .build();
    }
}

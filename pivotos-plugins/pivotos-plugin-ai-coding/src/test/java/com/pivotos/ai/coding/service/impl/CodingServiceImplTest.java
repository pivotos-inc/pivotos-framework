package com.pivotos.ai.coding.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.pivotos.ai.coding.api.constant.CodingErrorCode;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.ai.coding.mapper.CodingSessionMapper;
import com.pivotos.ai.coding.service.IntentParseService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.generator.service.IGeneratorFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CodingServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodingServiceImpl unit tests")
class CodingServiceImplTest {

    @Mock
    private IntentParseService intentParseService;

    @Mock
    private IGeneratorFacade generatorFacade;

    @Mock
    private CodingSessionMapper sessionMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CodingServiceImpl codingService;

    private Map<String, Object> mockIntent;
    private Map<String, String> mockGeneratedFiles;

    @BeforeEach
    void setUp() throws Exception {
        // Build mock AI-parsed intent
        mockIntent = Map.of(
                "moduleName", "system",
                "tableName", "biz_product",
                "functionName", "Product Management",
                "businessName", "product",
                "tableComment", "Product table",
                "columns", List.of(
                        Map.of("columnName", "id", "type", "bigint", "comment", "Primary key",
                                "isPk", true, "isRequired", true),
                        Map.of("columnName", "product_name", "type", "varchar(100)", "comment", "Product name",
                                "isPk", false, "isRequired", true),
                        Map.of("columnName", "price", "type", "decimal(10,2)", "comment", "Price",
                                "isPk", false, "isRequired", true)
                )
        );

        // Mock generated files
        mockGeneratedFiles = Map.of(
                "entity/BizProduct.java", "package com.pivotos.system...",
                "controller/BizProductController.java", "package com.pivotos.system..." ,
                "views/system/product/index.vue", "<template>...</template>"
        );

    }

    // ==================== parseAndGenerate ====================

    @Test
    @DisplayName("parseAndGenerate - null description throws error")
    void testParseAndGenerateNullDescription() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGenerate(null));
        assertEquals(CodingErrorCode.CODING_DESC_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("parseAndGenerate - blank description throws error")
    void testParseAndGenerateBlankDescription() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGenerate("   "));
        assertEquals(CodingErrorCode.CODING_DESC_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("parseAndGenerate - intent parse failure throws error")
    void testParseAndGenerateParseFailure() {
        when(intentParseService.parse(anyString()))
                .thenThrow(new RuntimeException("AI service unavailable"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGenerate("Create a product management module"));
        assertEquals(CodingErrorCode.CODING_INTENT_PARSE_FAILED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("parseAndGenerate - missing required intent fields throws error")
    void testParseAndGenerateMissingFields() {
        // Missing "tableName" field
        Map<String, Object> incompleteIntent = Map.of("moduleName", "system",
                "columns", List.of(Map.of("name", "id")));
        when(intentParseService.parse(anyString())).thenReturn(incompleteIntent);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGenerate("Create something"));
        assertEquals(CodingErrorCode.CODING_INTENT_PARSE_FAILED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("parseAndGenerate - happy path generates code and saves session")
    void testParseAndGenerateSuccess() throws JacksonException {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"entity/BizProduct.java\":\"test\"}");
        when(intentParseService.parse(anyString())).thenReturn(mockIntent);
        when(generatorFacade.importTable(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(10L);
        when(generatorFacade.previewCode(10L)).thenReturn(mockGeneratedFiles);
        when(sessionMapper.insert(any(CodingSession.class))).thenReturn(1);

        CodingSessionVO vo = codingService.parseAndGenerate("Create a product management module");

        assertNotNull(vo);
        assertEquals("system", vo.getModuleName());
        assertEquals("biz_product", vo.getTableName());
        assertEquals("Product Management", vo.getFunctionName());
        assertEquals("product", vo.getBusinessName());
        assertEquals(mockGeneratedFiles, vo.getGeneratedFiles());

        // Verify session was saved
        ArgumentCaptor<CodingSession> captor = ArgumentCaptor.forClass(CodingSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals("Create a product management module", captor.getValue().getDescription());
        assertEquals(1, captor.getValue().getStatus()); // pending review
    }

    // ==================== getSession ====================

    @Test
    @DisplayName("getSession - not found throws error")
    void testGetSessionNotFound() {
        when(sessionMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.getSession(1L, 999L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("getSession - found returns VO")
    void testGetSessionFound() throws Exception {
        CodingSession session = buildMockSession();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(objectMapper.readValue(anyString(), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(mockGeneratedFiles);

        CodingSessionVO vo = codingService.getSession(1L, 1L);

        assertNotNull(vo);
        assertEquals(1L, vo.getId());
        assertEquals("Create a product management module", vo.getDescription());
        assertEquals(mockGeneratedFiles, vo.getGeneratedFiles());
    }

    @Test
    @DisplayName("getSession - cross-user access throws 7004 (row-level isolation)")
    void testGetSessionCrossUser() {
        CodingSession session = buildMockSession(); // createBy = 1
        when(sessionMapper.selectById(1L)).thenReturn(session);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.getSession(2L, 1L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("getSession - null createBy treated as not owned")
    void testGetSessionNullCreateBy() {
        CodingSession session = buildMockSession();
        session.setCreateBy(null);
        when(sessionMapper.selectById(1L)).thenReturn(session);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.getSession(1L, 1L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== applyToProject ====================

    @Test
    @DisplayName("applyToProject - session not found throws error")
    void testApplyToProjectNotFound() {
        when(sessionMapper.selectById(999L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.applyToProject(1L, 999L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("applyToProject - updates status to applied")
    void testApplyToProjectSuccess() {
        CodingSession session = buildMockSession();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(sessionMapper.updateById(any(CodingSession.class))).thenReturn(1);

        codingService.applyToProject(1L, 1L);

        verify(generatorFacade).generateToProject("biz_product");
        assertEquals(2, session.getStatus());
    }

    @Test
    @DisplayName("applyToProject - cross-user apply rejected, no code written")
    void testApplyToProjectCrossUser() {
        CodingSession session = buildMockSession(); // createBy = 1
        when(sessionMapper.selectById(1L)).thenReturn(session);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.applyToProject(2L, 1L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
        verify(generatorFacade, never()).generateToProject(anyString());
        verify(sessionMapper, never()).updateById(any(CodingSession.class));
    }

    // ==================== Helper ====================

    private CodingSession buildMockSession() {
        CodingSession session = new CodingSession();
        session.setId(1L);
        session.setCreateBy(1L);
        session.setDescription("Create a product management module");
        session.setModuleName("system");
        session.setTableName("biz_product");
        session.setFunctionName("Product Management");
        session.setBusinessName("product");
        session.setStatus(1);
        session.setGeneratedFilesJson("{\"entity/BizProduct.java\":\"test\"}");
        return session;
    }
}

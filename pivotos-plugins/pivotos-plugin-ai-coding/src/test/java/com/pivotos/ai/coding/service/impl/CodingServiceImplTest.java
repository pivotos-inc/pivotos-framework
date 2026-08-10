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

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    @Mock
    private com.pivotos.ai.coding.service.ErrorCodeSegmentAllocator segmentAllocator;

    @Mock
    private com.pivotos.ai.coding.service.ArtifactLinter artifactLinter;

    @Mock
    private com.pivotos.ai.coding.service.AssemblyPatcher assemblyPatcher;

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

    // ==================== Plugin 骨架（S42） ====================

    @Test
    @DisplayName("parseAndGeneratePlugin - happy path saves taskType=2 session")
    void testParseAndGeneratePluginSuccess(@TempDir Path tempDir) throws Exception {
        Map<String, Object> intent = Map.of(
                "pluginName", "asset",
                "displayName", "资产管理",
                "tablePrefix", "asset_",
                "moduleDesc", "资产台账与领用管理");
        when(intentParseService.parsePluginIntent(anyString())).thenReturn(intent);
        when(assemblyPatcher.resolveFrameworkRoot()).thenReturn(tempDir);
        when(segmentAllocator.allocateFreeSegment()).thenReturn(8);
        Map<String, String> files = Map.of(
                "pivotos-plugins/pivotos-plugin-asset/pom.xml", "<project/>",
                "pivotos-plugins/pivotos-plugin-asset-api/pom.xml", "<project/>");
        when(generatorFacade.previewPluginSkeleton(any())).thenReturn(files);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(sessionMapper.insert(any(CodingSession.class))).thenReturn(1);

        CodingSessionVO vo = codingService.parseAndGeneratePlugin("做一个资产管理插件");

        assertNotNull(vo);
        assertEquals(2, vo.getTaskType());
        assertEquals("asset", vo.getModuleName());
        assertEquals("资产管理", vo.getFunctionName());

        ArgumentCaptor<CodingSession> captor = ArgumentCaptor.forClass(CodingSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getTaskType());
        assertEquals("asset", captor.getValue().getModuleName());

        // 参数传递：错误码段 8000
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(generatorFacade).previewPluginSkeleton(paramsCaptor.capture());
        assertEquals(8000, paramsCaptor.getValue().get("errorCodeBase"));
        assertEquals("Asset", paramsCaptor.getValue().get("className"));
    }

    @Test
    @DisplayName("parseAndGeneratePlugin - invalid plugin name rejected")
    void testParseAndGeneratePluginInvalidName(@TempDir Path tempDir) {
        when(intentParseService.parsePluginIntent(anyString()))
                .thenReturn(Map.of("pluginName", "System", "displayName", "x"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGeneratePlugin("做个插件"));
        assertEquals(CodingErrorCode.CODING_PLUGIN_NAME_INVALID.getCode(), ex.getCode());
        verify(generatorFacade, never()).previewPluginSkeleton(any());
    }

    @Test
    @DisplayName("parseAndGeneratePlugin - existing plugin dir rejected")
    void testParseAndGeneratePluginDirExists(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve("pivotos-plugins/pivotos-plugin-asset"));
        when(intentParseService.parsePluginIntent(anyString()))
                .thenReturn(Map.of("pluginName", "asset", "displayName", "资产管理"));
        when(assemblyPatcher.resolveFrameworkRoot()).thenReturn(tempDir);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.parseAndGeneratePlugin("做个资产插件"));
        assertEquals(CodingErrorCode.CODING_PLUGIN_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("applyToProject - skeleton lint violation rejected")
    void testApplySkeletonLintRejected() throws Exception {
        CodingSession session = buildPluginSession();
        when(sessionMapper.selectById(9L)).thenReturn(session);
        when(objectMapper.readValue(anyString(), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(Map.of())
                .thenReturn(Map.of("pluginName", "asset", "displayName", "资产管理"));
        when(artifactLinter.lint(any())).thenReturn(List.of("R2 pom.xml: 禁裸线程"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.applyToProject(1L, 9L));
        assertEquals(CodingErrorCode.CODING_LINT_FAILED.getCode(), ex.getCode());
        verify(assemblyPatcher, never()).patch(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("applyToProject - skeleton path outside whitelist rejected")
    void testApplySkeletonPathRejected() throws Exception {
        CodingSession session = buildPluginSession();
        session.setGeneratedFilesJson("{\"pivotos-ui/apps/x.vue\":\"x\"}");
        when(sessionMapper.selectById(9L)).thenReturn(session);
        when(artifactLinter.lint(any())).thenReturn(List.of());
        when(objectMapper.readValue(anyString(), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(Map.of("pivotos-ui/apps/x.vue", "x"))
                .thenReturn(Map.of("pluginName", "asset"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingService.applyToProject(1L, 9L));
        assertEquals(CodingErrorCode.CODING_PATH_REJECTED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("applyToProject - skeleton success writes files and patches assembly")
    void testApplySkeletonSuccess(@TempDir Path tempDir) throws Exception {
        CodingSession session = buildPluginSession();
        when(sessionMapper.selectById(9L)).thenReturn(session);
        when(artifactLinter.lint(any())).thenReturn(List.of());
        Map<String, String> files = Map.of("pivotos-plugins/pivotos-plugin-asset/pom.xml", "<project/>");
        when(objectMapper.readValue(anyString(), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(files)
                .thenReturn(Map.of("pluginName", "asset", "displayName", "资产管理"));
        when(assemblyPatcher.resolveFrameworkRoot()).thenReturn(tempDir);
        when(sessionMapper.updateById(any(CodingSession.class))).thenReturn(1);

        codingService.applyToProject(1L, 9L);

        assertTrue(java.nio.file.Files.exists(
                tempDir.resolve("pivotos-plugins/pivotos-plugin-asset/pom.xml")));
        verify(assemblyPatcher).patch(tempDir, "asset", "资产管理");
        assertEquals(2, session.getStatus());
    }

    private CodingSession buildPluginSession() {
        CodingSession session = buildMockSession();
        session.setId(9L);
        session.setTaskType(2);
        session.setModuleName("asset");
        session.setFunctionName("资产管理");
        session.setGeneratedFilesJson("{}");
        session.setExtraJson("{\"pluginName\":\"asset\",\"displayName\":\"资产管理\"}");
        return session;
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

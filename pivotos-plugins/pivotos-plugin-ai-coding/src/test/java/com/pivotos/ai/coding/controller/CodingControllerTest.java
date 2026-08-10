package com.pivotos.ai.coding.controller;

import com.pivotos.ai.coding.api.constant.CodingErrorCode;
import com.pivotos.ai.coding.api.dto.CodingRequest;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CodingController.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodingController unit tests")
class CodingControllerTest {

    @Mock
    private CodingService codingService;

    @InjectMocks
    private CodingController codingController;

    @BeforeEach
    void setUp() {
        // ready
    }

    // ==================== parse ====================

    @Test
    @DisplayName("parse - success returns R.ok with session VO")
    void testParseSuccess() {
        CodingSessionVO mockVO = CodingSessionVO.builder()
                .id(1L)
                .description("Create a product management module")
                .moduleName("system")
                .tableName("biz_product")
                .functionName("Product Management")
                .businessName("product")
                .status(1)
                .generatedFiles(Map.of("entity/BizProduct.java", "package com.pivotos..."))
                .build();

        when(codingService.parseAndGenerate(anyString())).thenReturn(mockVO);

        CodingRequest request = new CodingRequest();
        request.setDescription("Create a product management module");

        R<CodingSessionVO> result = codingController.parse(request);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(1L, result.getData().getId());
        assertEquals("system", result.getData().getModuleName());
        assertEquals("biz_product", result.getData().getTableName());
        assertEquals(1, result.getData().getStatus());
        assertNotNull(result.getData().getGeneratedFiles());
        assertTrue(result.getData().getGeneratedFiles().containsKey("entity/BizProduct.java"));
    }

    @Test
    @DisplayName("parse - empty description returns error code")
    void testParseEmptyDescription() {
        when(codingService.parseAndGenerate(anyString()))
                .thenThrow(new ServiceException(CodingErrorCode.CODING_DESC_EMPTY));

        CodingRequest request = new CodingRequest();
        request.setDescription("");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingController.parse(request));
        assertEquals(CodingErrorCode.CODING_DESC_EMPTY.getCode(), ex.getCode());
    }

    // ==================== session ====================

    @Test
    @DisplayName("session - success returns VO")
    void testSessionSuccess() {
        CodingSessionVO mockVO = CodingSessionVO.builder()
                .id(1L)
                .description("Test description")
                .moduleName("system")
                .tableName("sys_user")
                .status(2)
                .build();

        when(codingService.getSession(1L)).thenReturn(mockVO);

        R<CodingSessionVO> result = codingController.session(1L);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(1L, result.getData().getId());
    }

    @Test
    @DisplayName("session - not found returns error")
    void testSessionNotFound() {
        when(codingService.getSession(999L))
                .thenThrow(new ServiceException(CodingErrorCode.CODING_SESSION_NOT_FOUND));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingController.session(999L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== apply ====================

    @Test
    @DisplayName("apply - success returns R.ok")
    void testApplySuccess() {
        doNothing().when(codingService).applyToProject(1L);

        R<Void> result = codingController.apply(1L);

        assertNotNull(result);
        assertEquals(0, result.getCode());
        verify(codingService).applyToProject(1L);
    }

    @Test
    @DisplayName("apply - not found returns error")
    void testApplyNotFound() {
        doThrow(new ServiceException(CodingErrorCode.CODING_SESSION_NOT_FOUND))
                .when(codingService).applyToProject(999L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingController.apply(999L));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }
}

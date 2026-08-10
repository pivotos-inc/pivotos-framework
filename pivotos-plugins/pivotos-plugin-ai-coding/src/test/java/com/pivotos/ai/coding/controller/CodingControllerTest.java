package com.pivotos.ai.coding.controller;

import com.pivotos.ai.coding.api.constant.CodingErrorCode;
import com.pivotos.ai.coding.api.dto.CodingRequest;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CodingController.
 * <p>
 * Controller 经 requireUserId() 读取 LoginContext（ScopedValue），
 * 测试用 LoginContext.KEY.where(...).call/run 显式绑定登录用户。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodingController unit tests")
class CodingControllerTest {

    private static final LoginUser USER = new LoginUser(1L, "admin", "sys-user", 0L);

    @Mock
    private CodingService codingService;

    @InjectMocks
    private CodingController codingController;

    /** 在绑定登录用户的上下文中执行 */
    private <T> T asUser(Supplier<T> action) {
        return ScopedValue.where(LoginContext.KEY, USER).call(action::get);
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
    @DisplayName("session - success returns VO (owner)")
    void testSessionSuccess() {
        CodingSessionVO mockVO = CodingSessionVO.builder()
                .id(1L)
                .description("Test description")
                .moduleName("system")
                .tableName("sys_user")
                .status(2)
                .build();

        when(codingService.getSession(1L, 1L)).thenReturn(mockVO);

        R<CodingSessionVO> result = asUser(() -> codingController.session(1L));

        assertNotNull(result);
        assertEquals(0, result.getCode());
        assertEquals(1L, result.getData().getId());
    }

    @Test
    @DisplayName("session - not found / cross-user returns error")
    void testSessionNotFound() {
        when(codingService.getSession(1L, 999L))
                .thenThrow(new ServiceException(CodingErrorCode.CODING_SESSION_NOT_FOUND));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> asUser(() -> codingController.session(999L)));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("session - no login context throws 1002")
    void testSessionNoLogin() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> codingController.session(1L));
        assertEquals(GlobalErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(codingService, never()).getSession(any(), any());
    }

    // ==================== apply ====================

    @Test
    @DisplayName("apply - success returns R.ok")
    void testApplySuccess() {
        doNothing().when(codingService).applyToProject(1L, 1L);

        R<Void> result = asUser(() -> codingController.apply(1L));

        assertNotNull(result);
        assertEquals(0, result.getCode());
        verify(codingService).applyToProject(1L, 1L);
    }

    @Test
    @DisplayName("apply - not found returns error")
    void testApplyNotFound() {
        doThrow(new ServiceException(CodingErrorCode.CODING_SESSION_NOT_FOUND))
                .when(codingService).applyToProject(1L, 999L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> asUser(() -> codingController.apply(999L)));
        assertEquals(CodingErrorCode.CODING_SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== pageSessions ====================

    @Test
    @DisplayName("pageSessions - passes current user id to service")
    void testPageSessionsPassesUserId() {
        when(codingService.pageSessions(1L, 1, 10))
                .thenReturn(new com.pivotos.common.core.page.PageResult<CodingSessionVO>(
                        java.util.List.of(), 0L, 1, 10));

        R<com.pivotos.common.core.page.PageResult<CodingSessionVO>> result =
                asUser(() -> codingController.pageSessions(1, 10));

        assertEquals(0, result.getCode());
        verify(codingService).pageSessions(1L, 1, 10);
    }
}

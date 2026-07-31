package com.pivotos.starter.web.handler;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全局异常处理器单测
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void serviceExceptionShouldMapToR() {
        var entity = handler.handleServiceException(new ServiceException(2001, "用户不存在"));
        // 显式 application/json：避免 SSE 端点 Accept 协商失败成 500 空 body（S23）
        assertEquals(MediaType.APPLICATION_JSON, entity.getHeaders().getContentType());
        R<Void> r = entity.getBody();
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(2001, r.getCode());
        assertEquals("用户不存在", r.getMsg());
    }

    @Test
    void badRequestShouldMapToParamInvalid() {
        R<Void> r = handler.handleBadRequest(new IllegalArgumentException("bad")).getBody();
        assertNotNull(r);
        assertEquals(GlobalErrorCode.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    void unknownExceptionShouldMapToSystemError() {
        R<Void> r = handler.handleUnknown(new RuntimeException("boom")).getBody();
        assertNotNull(r);
        assertEquals(GlobalErrorCode.SYSTEM_ERROR.getCode(), r.getCode());
    }
}

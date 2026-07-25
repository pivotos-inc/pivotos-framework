package com.pivotos.starter.web.handler;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 全局异常处理器单测
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void serviceExceptionShouldMapToR() {
        R<Void> r = handler.handleServiceException(new ServiceException(2001, "用户不存在"));
        assertFalse(r.isSuccess());
        assertEquals(2001, r.getCode());
        assertEquals("用户不存在", r.getMsg());
    }

    @Test
    void badRequestShouldMapToParamInvalid() {
        R<Void> r = handler.handleBadRequest(new IllegalArgumentException("bad"));
        assertEquals(GlobalErrorCode.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    void unknownExceptionShouldMapToSystemError() {
        R<Void> r = handler.handleUnknown(new RuntimeException("boom"));
        assertEquals(GlobalErrorCode.SYSTEM_ERROR.getCode(), r.getCode());
    }
}

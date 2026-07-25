package com.pivotos.common.core.exception;

import com.pivotos.common.core.enums.error.ErrorCode;
import com.pivotos.common.core.enums.error.GlobalErrorCode;

/**
 * 业务异常：业务层统一抛出，由全局异常处理器转换为 R
 */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码 */
    private final int code;

    public ServiceException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public ServiceException(ErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
    }

    public ServiceException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public ServiceException(String msg) {
        this(GlobalErrorCode.SYSTEM_ERROR.getCode(), msg);
    }

    public int getCode() {
        return code;
    }
}

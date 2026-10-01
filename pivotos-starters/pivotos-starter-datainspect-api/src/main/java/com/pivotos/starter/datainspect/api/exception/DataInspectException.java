package com.pivotos.starter.datainspect.api.exception;

import com.pivotos.common.core.enums.error.ErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.datainspect.api.enums.DataInspectErrorCode;

/**
 * 数据监控域业务异常：一律经 {@link ServiceException} 承载业务码（项目红线：全项目 RuntimeException 已清零）。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class DataInspectException extends ServiceException {

    private static final long serialVersionUID = 1L;

    public DataInspectException(ErrorCode errorCode) {
        super(errorCode);
    }

    public DataInspectException(DataInspectErrorCode errorCode, String detail) {
        super(errorCode, detail == null ? errorCode.getMsg() : errorCode.getMsg() + "：" + detail);
    }

    public DataInspectException(DataInspectErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getCode(), (detail == null ? errorCode.getMsg() : errorCode.getMsg() + "：" + detail));
        initCause(cause);
    }
}

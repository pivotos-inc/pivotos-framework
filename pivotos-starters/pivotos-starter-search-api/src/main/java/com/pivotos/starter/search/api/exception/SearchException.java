package com.pivotos.starter.search.api.exception;

import com.pivotos.common.core.enums.error.ErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.search.api.enums.SearchErrorCode;

/**
 * 搜索域业务异常：一律经 {@link ServiceException} 承载业务码（项目红线：全项目 RuntimeException 已清零）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public class SearchException extends ServiceException {

    private static final long serialVersionUID = 1L;

    public SearchException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SearchException(SearchErrorCode errorCode, String detail) {
        super(errorCode, errorCode.getMsg() + "：" + detail);
    }

    public SearchException(SearchErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMsg() + "：" + detail);
        initCause(cause);
    }
}

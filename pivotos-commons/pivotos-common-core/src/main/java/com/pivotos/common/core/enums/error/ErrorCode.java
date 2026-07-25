package com.pivotos.common.core.enums.error;

/**
 * 错误码契约：各域错误码枚举统一实现此接口
 * 号段约定：0 成功；1xxx 通用；2xxx system 域；后续每域独占一段
 */
public interface ErrorCode {

    /**
     * 错误码
     */
    int getCode();

    /**
     * 错误提示
     */
    String getMsg();
}

package com.pivotos.common.core.enums.error;

/**
 * 通用错误码（1xxx 段）
 */
public enum GlobalErrorCode implements ErrorCode {

    /** 成功 */
    SUCCESS(0, "成功"),
    /** 参数校验失败 */
    PARAM_INVALID(1001, "参数校验失败"),
    /** 未认证或登录已过期 */
    UNAUTHORIZED(1002, "未认证或登录已过期"),
    /** 无访问权限 */
    FORBIDDEN(1003, "无访问权限"),
    /** 资源不存在 */
    NOT_FOUND(1004, "资源不存在"),
    /** 重复提交 */
    REPEAT_SUBMIT(1005, "请求重复提交，请稍后重试"),
    /** 请求限流 */
    RATE_LIMITED(1006, "请求过于频繁，请稍后重试"),
    /** 加解密失败 */
    CRYPTO_ERROR(1007, "接口加解密处理失败"),
    /** 文件大小超出限制 */
    FILE_TOO_LARGE(1008, "上传文件大小超出限制"),
    /** 系统内部错误 */
    SYSTEM_ERROR(1500, "系统内部错误"),
    ;

    private final int code;
    private final String msg;

    GlobalErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}

package com.pivotos.common.core.result;

import com.pivotos.common.core.enums.error.ErrorCode;
import com.pivotos.common.core.enums.error.GlobalErrorCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体
 *
 * @param <T> 数据载荷类型
 */
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务错误码，0 表示成功 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 数据载荷 */
    private T data;

    /** 链路追踪 ID（由 starter-core 的 TraceContext 写入，此处仅作载体） */
    private String traceId;

    public R() {
    }

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 成功（无数据）
     */
    public static <T> R<T> ok() {
        return new R<>(GlobalErrorCode.SUCCESS.getCode(), GlobalErrorCode.SUCCESS.getMsg(), null);
    }

    /**
     * 成功（带数据）
     */
    public static <T> R<T> ok(T data) {
        return new R<>(GlobalErrorCode.SUCCESS.getCode(), GlobalErrorCode.SUCCESS.getMsg(), data);
    }

    /**
     * 失败（按错误码枚举）
     */
    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    /**
     * 失败（自定义编码与信息）
     */
    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return this.code == GlobalErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}

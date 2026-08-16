package com.pivotos.docsync.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * docsync 域错误码（9xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>9000-9019 同步配置管理</li>
 *   <li>9020-9039 同步执行</li>
 *   <li>9040-9059 连通性测试</li>
 * </ul>
 */
public enum DocSyncErrorCode implements ErrorCode {

    // ---------- 同步配置管理 ----------
    CONFIG_NOT_FOUND(9000, "同步配置不存在"),
    CONFIG_NAME_EXISTS(9001, "配置名称已存在"),
    CONFIG_TYPE_NOT_SUPPORTED(9002, "不支持的文档平台类型"),
    CONFIG_TOKEN_MISSING(9003, "平台认证凭证未配置"),
    CONFIG_FIELD_REQUIRED(9004, "必填配置字段未填写"),

    // ---------- 同步执行 ----------
    SYNC_FAILED(9020, "文档同步失败"),
    SYNC_TIMEOUT(9021, "文档同步超时"),
    SYNC_REMOTE_ERROR(9022, "远端平台返回错误"),
    SYNC_NOT_SUPPORTED_AUTO(9023, "该平台不支持自动同步，请使用手动同步"),
    SYNC_OPENAPI_FETCH_FAILED(9024, "获取 OpenAPI JSON 失败，请检查 SpringDoc 是否已启用"),

    // ---------- 连通性测试 ----------
    CONNECTION_FAILED(9040, "平台连通性测试失败"),
    CONNECTION_AUTH_FAILED(9041, "平台认证失败，请检查 Token 或 API Key"),

    ;

    private final int code;
    private final String msg;

    DocSyncErrorCode(int code, String msg) {
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

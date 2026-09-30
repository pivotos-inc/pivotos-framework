package com.pivotos.starter.search.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * 搜索域错误码：81xx 段（8xxx 号段中 8000-8065 已由迁移域占用，此处另起 8100 起）。
 *
 * <p>号段分配：
 * <ul>
 *   <li>8100-8109 查询契约与参数</li>
 *   <li>8110-8119 Provider 与装配</li>
 *   <li>8120-8129 索引与文档操作</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public enum SearchErrorCode implements ErrorCode {

    // ---------- 查询契约与参数 ----------
    LAMBDA_FIELD_UNRESOLVED(8100, "搜索字段解析失败：Lambda 表达式不是合法的 getter 引用"),
    QUERY_PARAM_INVALID(8101, "搜索查询参数不合法"),
    QUERY_NOT_SUPPORTED(8102, "当前搜索实现不支持该查询能力"),

    // ---------- Provider 与装配 ----------
    PROVIDER_NOT_FOUND(8110, "未找到匹配的搜索实现"),
    PROVIDER_TYPE_INVALID(8111, "搜索实现类型不合法"),

    // ---------- 索引与文档操作 ----------
    INDEX_NAME_INVALID(8120, "搜索索引名不合法"),
    DOCUMENT_NOT_FOUND(8121, "搜索文档不存在"),
    SEARCH_EXECUTE_FAILED(8122, "搜索执行失败"),
    ;

    private final int code;
    private final String msg;

    SearchErrorCode(int code, String msg) {
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

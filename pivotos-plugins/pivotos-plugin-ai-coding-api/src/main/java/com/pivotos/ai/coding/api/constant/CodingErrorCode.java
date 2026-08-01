package com.pivotos.ai.coding.api.constant;

import com.pivotos.common.core.enums.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI Coding 错误码（7xxx）
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Getter
@AllArgsConstructor
public enum CodingErrorCode implements ErrorCode {

    /** 自然语言描述为空 */
    CODING_DESC_EMPTY(7001, "自然语言描述不能为空"),

    /** LLM 意图解析失败 */
    CODING_INTENT_PARSE_FAILED(7002, "AI 意图解析失败，请尝试用更明确的业务描述"),

    /** 生成器出码失败 */
    CODING_GENERATE_FAILED(7003, "代码生成失败"),

    /** 会话不存在 */
    CODING_SESSION_NOT_FOUND(7004, "AI Coding 会话不存在"),

    /** 表名冲突 */
    CODING_TABLE_EXISTS(7005, "目标表已存在，请修改表名"),

    ;

    private final int code;
    private final String msg;
}

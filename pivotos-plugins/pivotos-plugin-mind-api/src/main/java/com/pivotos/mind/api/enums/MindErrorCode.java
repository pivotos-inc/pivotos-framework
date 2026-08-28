package com.pivotos.mind.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * mind 域错误码（7xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>7000-7019 知识库</li>
 *   <li>7020-7039 待办</li>
 * </ul>
 */
public enum MindErrorCode implements ErrorCode {

    // ---------- 知识库 ----------
    KNOWLEDGE_NOT_FOUND(7000, "知识不存在"),
    KNOWLEDGE_TITLE_EMPTY(7001, "知识标题不能为空"),

    // ---------- 待办 ----------
    TODO_NOT_FOUND(7020, "待办不存在"),
    TODO_TITLE_EMPTY(7021, "待办标题不能为空"),
    TODO_AI_PARSE_FAILED(7022, "AI 未识别到可拆分的待办，请换一种描述");

    private final int code;
    private final String msg;

    MindErrorCode(int code, String msg) {
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

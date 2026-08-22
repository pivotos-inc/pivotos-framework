package com.pivotos.migration.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * migration 域错误码（8xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>8000-8019 任务管理</li>
 *   <li>8020-8039 文件上传与解析</li>
 *   <li>8040-8059 AI 分析与计划</li>
 *   <li>8060-8079 代码生成与执行</li>
 *   <li>8080-8099 评审与回滚</li>
 * </ul>
 */
public enum MigrationErrorCode implements ErrorCode {

    // ---------- 任务管理 ----------
    TASK_NOT_FOUND(8000, "迁移任务不存在"),
    TASK_STATUS_INVALID(8001, "当前任务状态不允许该操作"),

    // ---------- 文件上传与解析 ----------
    UPLOAD_FILE_EMPTY(8020, "上传文件不能为空"),
    UPLOAD_FILE_TOO_LARGE(8021, "上传文件超过大小限制"),
    UPLOAD_INVALID_ARCHIVE(8022, "无法解析上传的压缩包"),
    PARSE_FAILED(8023, "源码解析失败"),
    UNSUPPORTED_TECH_STACK(8024, "不支持的技术栈"),

    // ---------- AI 分析与计划 ----------
    ANALYZE_FAILED(8040, "架构分析失败"),
    PLAN_FAILED(8041, "迁移计划生成失败"),

    // ---------- 代码生成与执行 ----------
    STEP_NOT_FOUND(8060, "迁移步骤不存在"),
    STEP_STATUS_INVALID(8061, "当前步骤状态不允许该操作"),
    GENERATE_FAILED(8062, "代码生成失败"),
    SELF_TEST_FAILED(8063, "自测未通过"),
    ARTIFACT_NOT_FOUND(8064, "迁移产物不存在"),
    ARTIFACT_PATH_INVALID(8065, "产物路径不合法"),

    // ---------- 评审与回滚 ----------
    REVIEW_ACTION_INVALID(8080, "评审动作不合法"),
    ROLLBACK_FAILED(8081, "回滚失败");

    private final int code;
    private final String msg;

    MigrationErrorCode(int code, String msg) {
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

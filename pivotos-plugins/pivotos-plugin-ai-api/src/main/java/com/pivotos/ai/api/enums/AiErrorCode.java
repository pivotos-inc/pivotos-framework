package com.pivotos.ai.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * ai 域错误码（5xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>5000-5019 对话</li>
 *   <li>5020-5039 模型配置</li>
 *   <li>5040-5059 知识库/RAG（S20 预留）</li>
 *   <li>5060-5079 Agent/MCP/工具体系（S98 启用）</li>
 *   <li>5080-5099 AI 审批助手（S101 启用）</li>
 * </ul>
 */
public enum AiErrorCode implements ErrorCode {

    // ---------- 对话 ----------
    CONVERSATION_NOT_FOUND(5001, "会话不存在"),
    CHAT_CONTENT_EMPTY(5002, "对话内容不能为空"),
    CHAT_FAILED(5003, "AI 对话失败，请稍后重试"),
    CHART_GEN_FAILED(5004, "AI 图表生成失败，请换个说法再试"),
    CHART_QUESTION_EMPTY(5005, "图表描述不能为空"),

    // ---------- 模型配置 ----------
    AI_NOT_CONFIGURED(5020, "AI 模型未配置"),
    PROVIDER_NOT_FOUND(5021, "AI 供应商不存在或已停用"),
    NO_AVAILABLE_KEY(5022, "该供应商暂无可用 API Key"),
    MODEL_LIST_FAILED(5023, "模型列表查询失败，请检查 base-url 与 Key"),
    PROVIDER_CODE_DUPLICATE(5024, "供应商编码已存在"),
    API_KEY_NOT_FOUND(5025, "API Key 不存在"),
    API_KEY_EMPTY(5026, "API Key 不能为空"),

    // ---------- Agent/MCP/工具体系（S98） ----------
    AI_TOOL_NOT_FOUND(5060, "AI 工具不存在"),
    AI_TOOL_STATUS_INVALID(5061, "工具状态值非法（0正常 1停用）"),

    // ---------- A5-1 工具多步编排（S116 启用，承接 Agent/MCP 段） ----------
    ORCHESTRATOR_DISABLED(5062, "AI 工具编排未启用"),
    ORCHESTRATOR_PLAN_EMPTY(5063, "未能规划出可执行的工具调用链"),
    ORCHESTRATOR_TOOL_UNKNOWN(5064, "编排计划包含未注册或已停用的工具"),
    ORCHESTRATOR_ARGS_INVALID(5065, "编排计划参数不合法"),
    ORCHESTRATOR_REF_INVALID(5066, "编排计划引用了不合法的前序步骤结果"),
    ORCHESTRATOR_WRITE_AUTO_CONFIRM(5067, "编排计划不允许代为确认写操作"),
    ORCHESTRATOR_STEP_LIMIT(5068, "编排计划步骤数超过上限"),
    ORCHESTRATOR_PLAN_NOT_FOUND(5069, "编排计划不存在"),
    ORCHESTRATOR_STEP_FAILED(5070, "编排执行中断于此步骤"),

    // ---------- A5-2 编排可靠性（S117 启用：5071 起） ----------
    // 5070 是「执行中断于此步骤」，与下面两个语义不同：5070 说明「某步失败了」，
    // 5071 说明「失败太多，后续步骤根本没跑」，5072 说明「重试了但仍失败」。
    // 用户据此能区分「重试有没有用」，这是 S111 定下的错误码纪律。
    ORCHESTRATOR_CIRCUIT_BROKEN(5071, "连续步骤失败已达熔断阈值，编排已中止（后续步骤未执行）"),
    ORCHESTRATOR_RETRY_EXHAUSTED(5072, "该步骤重试后仍失败"),

    // ---------- S118 AI-2 四入口（SSE 入口启用：5073 起） ----------
    // SSE 入口内的异常必须落成 error 帧而非抛出（否则会被全局兜底改写成 HTTP 5xx，
    // 客户端拿不到结构化原因），非业务异常统一收敛到本码。
    AI_TOOL_STREAM_FAILED(5073, "AI 工具流式调用失败"),

    // ---------- AI 审批助手（S101） ----------
    APPROVAL_TASK_NOT_FOUND(5081, "待办任务不存在或已办结"),
    ADVICE_GEN_FAILED(5082, "AI 审批建议生成失败，请稍后重试"),
    APPROVAL_NOT_APPROVER(5083, "仅当前任务的审批人可生成建议"),

    // ---------- A4E 审批建议增强（S117 启用：5084 起） ----------
    APPROVAL_AUTO_DISABLED(5084, "受控自动预审未启用"),
    APPROVAL_AUTO_REJECTED(5085, "该待办不满足受控自动通过的确定性规则，请人工审批");

    private final int code;
    private final String msg;

    AiErrorCode(int code, String msg) {
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

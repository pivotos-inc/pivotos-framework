package com.pivotos.starter.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 平台级配置（{@code pivotos.ai.*}）。
 * 模型接入走 Spring AI 标准配置（spring.ai.openai.*），本类只管 PivotOS 平台行为；
 * api-key 未配置时端点返回明确错误码而非启动失败（与 file 插件同款约定）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.ai")
public class AiProperties {

    /** 默认系统提示词（ChatClient defaultSystem） */
    private String systemPrompt = "你是磐维 PivotOS 企业管理平台的 AI 助手，用简体中文简洁、准确地回答问题。";

    /** 单次对话携带的历史消息条数上限（多轮记忆窗口） */
    private Integer maxHistory = 20;
}

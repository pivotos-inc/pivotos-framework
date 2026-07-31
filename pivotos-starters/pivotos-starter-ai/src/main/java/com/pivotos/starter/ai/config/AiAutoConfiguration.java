package com.pivotos.starter.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * AI 能力自动配置：
 * ChatModel 由 Spring AI openai-starter 按 spring.ai.openai.* 自动装配（DashScope 兼容模式），
 * ChatClient.Builder 由官方 ChatClientAutoConfiguration 基于 ChatModel 提供；
 * 本类在其之上注册带默认系统提示词的 ChatClient，业务侧（plugin-ai）直接注入使用。
 * afterName 排序确保 @ConditionalOnBean 评估时官方装配已完成（否则条件恒不匹配）。
 * ConditionalOnMissingBean 防御兜底，应用可自定义 ChatClient 替换。
 */
@AutoConfiguration(afterName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration")
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatClient.Builder builder, AiProperties properties) {
        log.info("[PivotOS] AI 能力装配：ChatClient 就绪");
        return builder.defaultSystem(properties.getSystemPrompt())
                .build();
    }
}

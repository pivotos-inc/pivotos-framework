package com.pivotos.ai.config;

import com.pivotos.ai.client.AiUsageRecorder;
import com.pivotos.ai.client.DelegatingEmbeddingModel;
import com.pivotos.ai.service.AiProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 动态 EmbeddingModel 配置（组件扫描装配，同 plugin-ai 其他 @Component 一致）。
 *
 * <p>注册 {@link DelegatingEmbeddingModel} 为 {@code @Primary EmbeddingModel}，
 * 使 VectorStore（Simple/Milvus）注入委托而非 Spring AI 静态装配的 OpenAiEmbeddingModel。
 * 委托优先从数据库解析 embedding/all 用途 Key，无则回退 {@code spring.ai.openai.*} 静态配置。
 *
 * <p>不使用 {@code @ConditionalOnMissingBean}：Spring AI 的 {@code OpenAiEmbeddingModel}
 * 可能仍被装配（非 primary），但注入时 {@code @Primary} 保证本 Bean 胜出。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(EmbeddingModel.class)
public class AiEmbeddingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiEmbeddingAutoConfiguration.class);

    @Bean
    @Primary
    public DelegatingEmbeddingModel delegatingEmbeddingModel(
            ObjectProvider<AiProviderService> providerServiceProvider,
            Environment environment,
            ObjectProvider<AiUsageRecorder> usageRecorderProvider) {
        log.info("[PivotOS] 动态 EmbeddingModel 装配：@Primary 委托就绪（数据库 Key 优先 → 静态兜底）");
        return new DelegatingEmbeddingModel(providerServiceProvider, environment,
                usageRecorderProvider.getIfAvailable());
    }
}

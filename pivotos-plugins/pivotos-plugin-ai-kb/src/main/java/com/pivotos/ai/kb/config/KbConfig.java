package com.pivotos.ai.kb.config;

import com.pivotos.ai.kb.extractor.OcrExtractor;
import com.pivotos.ai.kb.vectorstore.KbVectorStoreProvider;
import com.pivotos.ai.kb.vectorstore.SimpleVectorStoreProvider;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库插件配置装配：SimpleVectorStore 提供者（零中间件兜底，默认始终装配）。
 * <p>Milvus 提供者因依赖 Spring AI 自动装配的 {@code MilvusVectorStore} Bean，
 * 已迁至 {@link KbMilvusAutoConfiguration} 通过 {@code @AutoConfiguration(afterName=...)}
 * 保证评估顺序。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KbProperties.class, KbDocSearchProperties.class})
public class KbConfig {

    @Bean
    public KbVectorStoreProvider simpleVectorStoreProvider(EmbeddingModel embeddingModel, KbProperties properties) {
        return new SimpleVectorStoreProvider(embeddingModel, properties);
    }

    @Bean
    public OcrExtractor ocrExtractor(KbProperties properties) {
        return new OcrExtractor(properties);
    }
}

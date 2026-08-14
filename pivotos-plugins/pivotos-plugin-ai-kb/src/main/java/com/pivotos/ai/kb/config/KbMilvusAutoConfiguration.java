package com.pivotos.ai.kb.config;

import com.pivotos.ai.kb.vectorstore.KbVectorStoreProvider;
import com.pivotos.ai.kb.vectorstore.MilvusVectorStoreProvider;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Milvus 向量存储提供者自动装配。
 * <p>使用 {@code @ConditionalOnProperty} 而非 {@code @ConditionalOnBean} 判断 Milvus 是否启用：
 * Spring Boot auto-configuration 的 {@code @ConditionalOnBean} 在配置类处理阶段评估，
 * 即使 {@code @AutoConfiguration(afterName=...)} 保证排序，Bean 定义注册与条件评估
 * 仍存在时序窗口，导致 {@code @ConditionalOnBean(MilvusVectorStore.class)} 恒不匹配。
 * 改为按属性 {@code spring.ai.vectorstore.milvus.client.host} 判断，避免 Bean 时序依赖。
 */
@AutoConfiguration(afterName = "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration")
public class KbMilvusAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.vectorstore.milvus.client", name = "host")
    public KbVectorStoreProvider milvusVectorStoreProvider(MilvusVectorStore milvusVectorStore) {
        return new MilvusVectorStoreProvider(milvusVectorStore);
    }
}

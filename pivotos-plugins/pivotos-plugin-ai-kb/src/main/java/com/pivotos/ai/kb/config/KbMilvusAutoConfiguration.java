package com.pivotos.ai.kb.config;

import com.pivotos.ai.kb.vectorstore.KbVectorStoreProvider;
import com.pivotos.ai.kb.vectorstore.MilvusVectorStoreProvider;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Milvus 向量存储提供者自动装配。
 * <p>必须在 Spring AI 的 {@code MilvusVectorStoreAutoConfiguration} 之后评估，
 * 否则 {@code @ConditionalOnBean(MilvusVectorStore.class)} 会因 Bean 尚未创建而恒不匹配。
 */
@AutoConfiguration(afterName = "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration")
public class KbMilvusAutoConfiguration {

    @Bean
    @ConditionalOnBean(MilvusVectorStore.class)
    public KbVectorStoreProvider milvusVectorStoreProvider(MilvusVectorStore milvusVectorStore) {
        return new MilvusVectorStoreProvider(milvusVectorStore);
    }
}

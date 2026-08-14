package com.pivotos.ai.kb.vectorstore;

import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 可插拔向量存储提供者接口。
 * 每种后端实现一个 Provider，由 {@link KbVectorStoreFactory} 按知识库维度动态选择。
 */
public interface KbVectorStoreProvider {

    /**
     * 是否支持指定知识库
     *
     * @param kb 知识库
     * @return true 表示支持
     */
    boolean supports(KnowledgeBase kb);

    /**
     * 获取该知识库对应的 VectorStore 实例
     *
     * @param kb 知识库
     * @return VectorStore
     */
    VectorStore getVectorStore(KnowledgeBase kb);
}

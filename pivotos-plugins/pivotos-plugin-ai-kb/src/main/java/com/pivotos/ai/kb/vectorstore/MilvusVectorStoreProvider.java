package com.pivotos.ai.kb.vectorstore;

import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.enums.KbVectorStoreTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

/**
 * Milvus 向量存储提供者。
 * 复用 Spring AI 自动装配的 {@link MilvusVectorStore} 单例，通过 {@code kb_id/doc_id} 元数据过滤隔离不同知识库。
 */
@RequiredArgsConstructor
public class MilvusVectorStoreProvider implements KbVectorStoreProvider {

    private final MilvusVectorStore vectorStore;

    @Override
    public boolean supports(KnowledgeBase kb) {
        return KbVectorStoreTypeEnum.MILVUS.getValue().equalsIgnoreCase(kb.getVectorStoreType());
    }

    @Override
    public VectorStore getVectorStore(KnowledgeBase kb) {
        return vectorStore;
    }
}

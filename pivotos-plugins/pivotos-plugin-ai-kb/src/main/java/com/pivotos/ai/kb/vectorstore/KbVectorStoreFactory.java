package com.pivotos.ai.kb.vectorstore;

import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库向量存储工厂：按知识库配置的向量存储类型选择对应的 Provider。
 */
@Component
@RequiredArgsConstructor
public class KbVectorStoreFactory {

    private final List<KbVectorStoreProvider> providers;

    /**
     * 获取指定知识库的 VectorStore 实现
     *
     * @param kb 知识库
     * @return VectorStore
     */
    public VectorStore get(KnowledgeBase kb) {
        if (kb == null || kb.getVectorStoreType() == null) {
            throw new IllegalArgumentException("知识库或向量存储类型不能为空");
        }
        return providers.stream()
                .filter(p -> p.supports(kb))
                .findFirst()
                .map(p -> p.getVectorStore(kb))
                .orElseThrow(() -> new IllegalStateException(
                        "未找到向量存储提供者: " + kb.getVectorStoreType()));
    }
}

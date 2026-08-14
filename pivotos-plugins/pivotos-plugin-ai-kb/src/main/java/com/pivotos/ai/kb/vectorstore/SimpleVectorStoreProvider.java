package com.pivotos.ai.kb.vectorstore;

import com.pivotos.ai.kb.config.KbProperties;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.enums.KbVectorStoreTypeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SimpleVectorStore 提供者：内存向量存储 + JSON 快照持久化。
 * 所有知识库共享同一个 SimpleVectorStore 实例，通过 {@code kb_id/doc_id} 元数据过滤隔离。
 */
@Slf4j
@RequiredArgsConstructor
public class SimpleVectorStoreProvider implements KbVectorStoreProvider {

    private final EmbeddingModel embeddingModel;
    private final KbProperties properties;

    private SimpleVectorStore vectorStore;
    private File snapshotFile;

    @PostConstruct
    public void init() throws IOException {
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        Path dir = Path.of(properties.getVectorStore().getSimple().getDataPath());
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        this.snapshotFile = dir.resolve(properties.getVectorStore().getSimple().getFileName()).toFile();
        if (snapshotFile.exists()) {
            try {
                vectorStore.load(snapshotFile);
                log.info("[PivotOS-KB] SimpleVectorStore 已从快照加载: {}", snapshotFile.getAbsolutePath());
            } catch (Exception e) {
                log.warn("[PivotOS-KB] SimpleVectorStore 快照加载失败，将使用空存储: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (vectorStore == null || snapshotFile == null) {
            return;
        }
        try {
            snapshotFile.getParentFile().mkdirs();
            vectorStore.save(snapshotFile);
            log.info("[PivotOS-KB] SimpleVectorStore 快照已保存: {}", snapshotFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("[PivotOS-KB] SimpleVectorStore 快照保存失败: {}", e.getMessage());
        }
    }

    @Override
    public boolean supports(KnowledgeBase kb) {
        return KbVectorStoreTypeEnum.SIMPLE.getValue().equalsIgnoreCase(kb.getVectorStoreType());
    }

    @Override
    public VectorStore getVectorStore(KnowledgeBase kb) {
        return vectorStore;
    }
}

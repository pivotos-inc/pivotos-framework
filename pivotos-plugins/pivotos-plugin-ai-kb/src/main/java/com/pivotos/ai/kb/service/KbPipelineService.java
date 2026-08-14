package com.pivotos.ai.kb.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.enums.KbDocStatusEnum;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.vectorstore.KbVectorStoreFactory;
import com.pivotos.file.api.facade.IFileFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * RAG 管线服务：下载 → Tika 解析 → 分块 → Embedding → VectorStore。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbPipelineService {

    private final KbVectorStoreFactory vectorStoreFactory;
    private final IFileFacade fileFacade;
    private final KbDocumentMapper documentMapper;

    /**
     * 对指定文档执行向量化索引。
     *
     * @param kb  知识库
     * @param doc 文档记录
     */
    public void index(KnowledgeBase kb, KbDocument doc) {
        updateDocStatus(doc, KbDocStatusEnum.INDEXING, null);
        try {
            String downloadUrl = resolveDownloadUrl(doc.getFileUrl());
            List<Document> rawDocuments = new TikaDocumentReader(downloadUrl).get();

            // 注入知识库/文档元数据，便于后续按 kb_id / doc_id 过滤与删除
            rawDocuments.forEach(d -> {
                d.getMetadata().put("kb_id", kb.getId().toString());
                d.getMetadata().put("doc_id", doc.getId().toString());
                d.getMetadata().put("file_name", StringUtils.hasText(doc.getFileName()) ? doc.getFileName() : "");
            });

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(doc.getChunkSize() != null && doc.getChunkSize() > 0 ? doc.getChunkSize() : 500)
                    .build();
            List<Document> chunks = splitter.apply(rawDocuments);

            VectorStore vectorStore = vectorStoreFactory.get(kb);
            // DashScope Embedding API 限制单次 batch ≤ 10，分批写入
            int batchSize = 10;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
                vectorStore.add(batch);
                log.debug("[PivotOS-KB] 向量批次写入: kbId={}, docId={}, batch={}/{}, size={}",
                        kb.getId(), doc.getId(), i / batchSize + 1,
                        (chunks.size() + batchSize - 1) / batchSize, batch.size());
            }

            doc.setStatus(KbDocStatusEnum.COMPLETED.getValue());
            doc.setVectorCount(chunks.size());
            // LambdaUpdateWrapper 显式 set null，绕过 MyBatis-Plus NOT_NULL 策略
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, doc.getId())
                    .set(KbDocument::getStatus, KbDocStatusEnum.COMPLETED.getValue())
                    .set(KbDocument::getVectorCount, chunks.size())
                    .set(KbDocument::getErrorMsg, null));
            log.info("[PivotOS-KB] 文档向量化完成: kbId={}, docId={}, chunks={}", kb.getId(), doc.getId(), chunks.size());
        } catch (Exception e) {
            log.error("[PivotOS-KB] 文档向量化失败: kbId={}, docId={}", kb.getId(), doc.getId(), e);
            doc.setStatus(KbDocStatusEnum.FAILED.getValue());
            doc.setErrorMsg(e.getMessage());
            documentMapper.updateById(doc);
        }
    }

    /**
     * 删除知识库下所有向量。
     *
     * @param kb 知识库
     */
    public void deleteByKb(KnowledgeBase kb) {
        try {
            VectorStore vectorStore = vectorStoreFactory.get(kb);
            vectorStore.delete("kb_id == '" + kb.getId() + "'");
            log.info("[PivotOS-KB] 已删除知识库向量: kbId={}", kb.getId());
        } catch (Exception e) {
            log.warn("[PivotOS-KB] 删除知识库向量失败（可能尚不存在）: kbId={}, reason={}",
                    kb.getId(), e.getMessage());
        }
    }

    /**
     * 删除指定文档的向量。
     *
     * @param kb  知识库
     * @param doc 文档记录
     */
    public void deleteByDoc(KnowledgeBase kb, KbDocument doc) {
        try {
            VectorStore vectorStore = vectorStoreFactory.get(kb);
            vectorStore.delete("doc_id == '" + doc.getId() + "'");
            log.info("[PivotOS-KB] 已删除文档向量: kbId={}, docId={}", kb.getId(), doc.getId());
        } catch (Exception e) {
            log.warn("[PivotOS-KB] 删除文档向量失败（可能尚不存在）: kbId={}, docId={}, reason={}",
                    kb.getId(), doc.getId(), e.getMessage());
        }
    }

    /**
     * 在指定知识库中检索相似文本块。
     *
     * @param kb    知识库
     * @param query 查询文本
     * @param topK  返回条数
     * @return 相似文档块
     */
    public List<Document> search(KnowledgeBase kb, String query, int topK) {
        VectorStore vectorStore = vectorStoreFactory.get(kb);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("kb_id == '" + kb.getId() + "'")
                .build();
        return vectorStore.similaritySearch(request);
    }

    private String resolveDownloadUrl(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new IllegalArgumentException("文档文件地址不能为空");
        }
        // 优先走文件 Facade 统一解析（支持对象 key / 预签名 URL / 公共 URL）
        try {
            return fileFacade.presignDownload(fileUrl);
        } catch (Exception e) {
            log.warn("[PivotOS-KB] 文件 Facade 解析失败，将直接使用原始地址: {}", e.getMessage());
            return fileUrl;
        }
    }

    private void updateDocStatus(KbDocument doc, KbDocStatusEnum status, String errorMsg) {
        doc.setStatus(status.getValue());
        doc.setErrorMsg(errorMsg);
        // LambdaUpdateWrapper 显式 set null，绕过 MyBatis-Plus NOT_NULL 策略
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, doc.getId())
                .set(KbDocument::getStatus, status.getValue())
                .set(KbDocument::getErrorMsg, errorMsg));
    }
}

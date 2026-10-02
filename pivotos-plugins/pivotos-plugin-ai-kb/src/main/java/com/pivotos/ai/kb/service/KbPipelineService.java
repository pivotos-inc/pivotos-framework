package com.pivotos.ai.kb.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pivotos.ai.api.facade.IRerankFacade;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.enums.KbDocStatusEnum;
import com.pivotos.ai.kb.extractor.OcrExtractor;
import com.pivotos.ai.kb.extractor.PdfTableExtractor;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.search.KbChunkSearchSupport;
import com.pivotos.ai.kb.retriever.KbFullTextRetriever;
import com.pivotos.ai.kb.retriever.KbHybridFusion;
import com.pivotos.ai.kb.retriever.RrfFusion;
import com.pivotos.ai.kb.search.KbDocSearchSupport;
import com.pivotos.ai.kb.splitter.SemanticChunkSplitter;
import com.pivotos.ai.kb.vectorstore.KbVectorStoreFactory;
import com.pivotos.file.api.facade.IFileFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final AiKbChunkMapper chunkMapper;
    private final PdfTableExtractor pdfTableExtractor;
    private final OcrExtractor ocrExtractor;
    /** 重排契约（S65）：plugin-ai 未装配或无配置时静默降级为原召回顺序 */
    private final ObjectProvider<IRerankFacade> rerankFacadeProvider;
    /** S122：文档状态/块数变化要同步回检索索引，否则列表页会看到过期的状态 */
    private final KbDocSearchSupport searchSupport;
    /** S128：文本块全文索引（召回的关键词通道） */
    private final KbChunkSearchSupport chunkSearchSupport;
    /** S128：全文通道（search 优先，回退库内 BM25） */
    private final KbFullTextRetriever fullTextRetriever;
    /** S128：双通道 RRF 融合（阈值与权重可配置） */
    private final KbHybridFusion hybridFusion;

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

            // 多模态解析增强：PDF 表格提取 + OCR
            String fileName = StringUtils.hasText(doc.getFileName()) ? doc.getFileName() : doc.getFileUrl();
            String fileType = determineFileType(fileName);
            StringBuilder additionalText = new StringBuilder();

            if ("pdf".equals(fileType)) {
                String tables = pdfTableExtractor.extractTables(downloadUrl);
                if (StringUtils.hasText(tables)) {
                    additionalText.append(tables);
                }
            }

            if (ocrExtractor.isAvailable()) {
                String ocrText = ocrExtractor.extractText(downloadUrl, fileType);
                if (StringUtils.hasText(ocrText)) {
                    additionalText.append(ocrText);
                }
            }

            if (StringUtils.hasText(additionalText.toString())) {
                Map<String, Object> extraMeta = new HashMap<>();
                extraMeta.put("kb_id", kb.getId().toString());
                extraMeta.put("doc_id", doc.getId().toString());
                extraMeta.put("file_name", fileName);
                if (!(rawDocuments instanceof ArrayList)) {
                    rawDocuments = new ArrayList<>(rawDocuments);
                }
                rawDocuments.add(new Document(additionalText.toString(), extraMeta));
            }

            int chunkSize = doc.getChunkSize() != null && doc.getChunkSize() > 0 ? doc.getChunkSize() : 500;
            int chunkOverlap = doc.getChunkOverlap() != null && doc.getChunkOverlap() > 0 ? doc.getChunkOverlap() : 100;
            SemanticChunkSplitter splitter = new SemanticChunkSplitter(chunkSize, chunkOverlap);
            List<Document> chunks = splitter.apply(rawDocuments);

            // S128：预计算内容 hash 并埋进向量元数据——<b>必须在 vectorStore.add 之前</b>，
            // 否则向量侧没有身份标识，RRF 的「两路都命中应加分」永远触发不了（S122 遗留②的隐性病灶）
            List<String> chunkHashes = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i).getText();
                String contentHash = md5Hex(content.length() > 100 ? content.substring(0, 100) : content);
                chunkHashes.add(contentHash);
                chunks.get(i).getMetadata().put(RrfFusion.META_CHUNK_HASH, contentHash);
            }

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

            // 同步写入 ai_kb_chunk 表（BM25 检索用）
            List<AiKbChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i).getText();
                String contentHash = chunkHashes.get(i);
                AiKbChunk chunkEntity = new AiKbChunk();
                chunkEntity.setKbId(kb.getId());
                chunkEntity.setDocId(doc.getId());
                chunkEntity.setChunkIndex(i);
                chunkEntity.setContent(content);
                chunkEntity.setContentHash(contentHash);
                chunkEntity.setTenantId(kb.getTenantId());
                chunkEntities.add(chunkEntity);
            }
            int chunkBatchSize = 200;
            for (int i = 0; i < chunkEntities.size(); i += chunkBatchSize) {
                List<AiKbChunk> batch = chunkEntities.subList(i, Math.min(i + chunkBatchSize, chunkEntities.size()));
                chunkMapper.batchInsert(batch);
            }
            log.info("[PivotOS-KB] 文本块写入完成: kbId={}, docId={}, chunks={}", kb.getId(), doc.getId(), chunkEntities.size());
            // S128：同步写全文索引（召回的关键词通道；simple 侧内存、es-java 侧引擎 BM25）
            chunkSearchSupport.indexBatch(chunkEntities);

            doc.setStatus(KbDocStatusEnum.COMPLETED.getValue());
            doc.setVectorCount(chunks.size());
            doc.setChunkCount(chunks.size());
            // LambdaUpdateWrapper 显式 set null，绕过 MyBatis-Plus NOT_NULL 策略
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, doc.getId())
                    .set(KbDocument::getStatus, KbDocStatusEnum.COMPLETED.getValue())
                    .set(KbDocument::getVectorCount, chunks.size())
                    .set(KbDocument::getChunkCount, chunks.size())
                    .set(KbDocument::getErrorMsg, null));
            // 同步检索索引：状态/块数已变，索引不跟上会导致列表页显示旧的「处理中」
            searchSupport.index(doc);
            log.info("[PivotOS-KB] 文档向量化完成: kbId={}, docId={}, chunks={}", kb.getId(), doc.getId(), chunks.size());
        } catch (Exception e) {
            log.error("[PivotOS-KB] 文档向量化失败: kbId={}, docId={}", kb.getId(), doc.getId(), e);
            doc.setStatus(KbDocStatusEnum.FAILED.getValue());
            doc.setErrorMsg(e.getMessage());
            documentMapper.updateById(doc);
            searchSupport.index(doc);
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
        chunkMapper.deleteByKbId(kb.getId());
        // S128：全文索引不跟着删，召回就会命中已删除知识库的块（比「少了召回」严重）
        chunkSearchSupport.deleteByKbId(kb.getId());
        log.info("[PivotOS-KB] 已删除知识库文本块: kbId={}", kb.getId());
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
        chunkMapper.deleteByDocId(doc.getId());
        chunkSearchSupport.deleteByDocId(doc.getId());
        log.info("[PivotOS-KB] 已删除文档文本块: kbId={}, docId={}", kb.getId(), doc.getId());
    }

    /**
     * 在指定知识库中检索相似文本块（混合检索：向量 + BM25 + RRF 融合 + rerank 精排）。
     *
     * <p>hybridSearch=true 时：向量 topK*3 + BM25 topK*3 → RRF 融合。
     * hybridSearch=false 时：仅向量检索。
     * rerank=true 时（S65）：候选扩至 topK*2，经 reranker 精排取 topK；
     * 无重排配置或调用失败时降级为原召回顺序（rerankScore=null）。
     *
     * @param kb    知识库
     * @param query 查询文本
     * @param topK  返回条数
     * @return 融合排序后的结果列表
     */
    public List<RrfFusion.FusedResult> search(KnowledgeBase kb, String query, int topK) {
        return search(kb, query, topK, null);
    }

    /**
     * 检索重载（S66 评测用）：rerankOverride 非空时临时覆盖知识库 rerank 开关，
     * 不影响知识库自身配置；传 null 时行为与三参方法一致。
     *
     * @param kb             知识库
     * @param query          查询文本
     * @param topK           返回条数
     * @param rerankOverride 重排开关覆盖（true/false 强制，null 跟随 kb.rerank）
     * @return 融合排序后的结果列表
     */
    public List<RrfFusion.FusedResult> search(KnowledgeBase kb, String query, int topK, Boolean rerankOverride) {
        VectorStore vectorStore = vectorStoreFactory.get(kb);
        boolean hybrid = !Boolean.FALSE.equals(kb.getHybridSearch());
        boolean rerankEnabled = rerankOverride != null ? rerankOverride : !Boolean.FALSE.equals(kb.getRerank());

        List<RrfFusion.FusedResult> candidates;
        if (!hybrid) {
            // 仅向量检索（重排开启时扩候选 topK*2，精排后再截断）
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(rerankEnabled ? topK * 2 : topK)
                    .filterExpression("kb_id == '" + kb.getId() + "'")
                    .build();
            List<Document> vectorResults = vectorStore.similaritySearch(request);
            candidates = new ArrayList<>();
            for (int i = 0; i < vectorResults.size(); i++) {
                Document d = vectorResults.get(i);
                candidates.add(new RrfFusion.FusedResult(d.getText(), d.getMetadata(), null, 0.0, i + 1, 0, null));
            }
        } else {
            // 混合检索：向量 topK*3 + 全文 topK*3 → RRF 融合（重排开启时扩候选 topK*2）
            // S128：全文通道改走 search 抽象（simple 侧确定性 BM25 / es-java 侧引擎 BM25），
            // 不可用时由 KbFullTextRetriever 回退库内 BM25；融合改用契约层 SearchRrf，阈值与权重可配置。
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 3)
                    .filterExpression("kb_id == '" + kb.getId() + "'")
                    .build();
            List<Document> vectorResults = vectorStore.similaritySearch(request);
            List<AiKbChunk> fullTextChunks = fullTextRetriever.retrieve(kb.getId(), query, topK * 3);
            candidates = hybridFusion.fuse(vectorResults, fullTextChunks, rerankEnabled ? topK * 2 : topK);
            log.info("[PivotOS-KB] 混合检索: kbId={}, vector={}, fulltext={}, fused={}",
                    kb.getId(), vectorResults.size(), fullTextChunks.size(), candidates.size());
        }

        if (!rerankEnabled) {
            return candidates;
        }
        return applyRerank(kb, query, candidates, topK);
    }

    /**
     * rerank 精排（S65）：facade 缺失/返回空（无配置或调用失败）时降级为原召回顺序。
     */
    private List<RrfFusion.FusedResult> applyRerank(KnowledgeBase kb, String query,
                                                    List<RrfFusion.FusedResult> candidates, int topK) {
        IRerankFacade reranker = rerankFacadeProvider.getIfAvailable();
        if (reranker == null || candidates.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        List<String> contents = candidates.stream().map(RrfFusion.FusedResult::content).toList();
        List<IRerankFacade.RerankResult> reranked = reranker.rerank(query, contents, topK);
        if (reranked.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        List<RrfFusion.FusedResult> results = new ArrayList<>(reranked.size());
        for (IRerankFacade.RerankResult r : reranked) {
            if (r.index() < 0 || r.index() >= candidates.size()) {
                continue;
            }
            results.add(candidates.get(r.index()).withRerankScore(r.score()));
        }
        log.info("[PivotOS-KB] rerank 精排: kbId={}, candidates={}, reranked={}",
                kb.getId(), candidates.size(), results.size());
        return results;
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
        searchSupport.index(doc);
    }

    /** 计算字符串 MD5（用于文本块 content_hash 去重） */
    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    /** 从文件名提取扩展名（小写，无扩展名返回空串） */
    private String determineFileType(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}

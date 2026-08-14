package com.pivotos.ai.kb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.domain.dto.KbDocPageQuery;
import com.pivotos.ai.kb.domain.dto.KbDocUploadRequest;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.AiKbChunkVO;
import com.pivotos.ai.kb.domain.vo.KbDocumentVO;
import com.pivotos.ai.kb.enums.KbDocStatusEnum;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.service.KbDocumentService;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识库文档管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class KbDocumentServiceImpl implements KbDocumentService {

    private final KbDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKbChunkMapper chunkMapper;
    private final KbPipelineService pipelineService;

    @Override
    public PageResult<KbDocumentVO> page(KbDocPageQuery query) {
        var wrapper = Wrappers.<KbDocument>lambdaQuery()
                .eq(query.getKbId() != null, KbDocument::getKbId, query.getKbId())
                .like(StringUtils.hasText(query.getFileName()), KbDocument::getFileName, query.getFileName())
                .eq(query.getStatus() != null, KbDocument::getStatus, query.getStatus())
                .orderByDesc(KbDocument::getCreateTime);
        Page<KbDocument> page = documentMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<KbDocumentVO> list = page.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public KbDocumentVO get(Long id) {
        KbDocument entity = requireDocument(id);
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long upload(KbDocUploadRequest request) {
        KnowledgeBase kb = requireKnowledgeBase(request.getKbId());
        KbDocument entity = new KbDocument();
        entity.setKbId(kb.getId());
        entity.setFileName(request.getFileName());
        entity.setFileUrl(request.getFileUrl());
        entity.setFileType(truncate(request.getFileType(), 200));
        entity.setFileSize(request.getFileSize() != null ? request.getFileSize() : 0L);
        entity.setChunkSize(kb.getChunkSize());
        entity.setChunkOverlap(kb.getChunkOverlap());
        entity.setStatus(KbDocStatusEnum.PENDING.getValue());
        entity.setVectorCount(0);
        entity.setChunkCount(0);
        documentMapper.insert(entity);

        // 同步触发向量化（大文件后续可改异步）
        pipelineService.index(kb, entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KbDocument entity = requireDocument(id);
        KnowledgeBase kb = requireKnowledgeBase(entity.getKbId());
        pipelineService.deleteByDoc(kb, entity);
        documentMapper.deleteById(id);
    }

    @Override
    public void reindex(Long id) {
        KbDocument entity = requireDocument(id);
        KnowledgeBase kb = requireKnowledgeBase(entity.getKbId());
        // 先删除旧向量，再重新索引
        pipelineService.deleteByDoc(kb, entity);
        pipelineService.index(kb, entity);
    }

    @Override
    public List<AiKbChunkVO> listChunks(Long id) {
        KbDocument entity = requireDocument(id);
        return chunkMapper.selectByDocId(entity.getId()).stream()
                .map(chunk -> {
                    AiKbChunkVO vo = new AiKbChunkVO();
                    vo.setId(chunk.getId());
                    vo.setChunkIndex(chunk.getChunkIndex());
                    vo.setContent(chunk.getContent());
                    vo.setContentHash(chunk.getContentHash());
                    return vo;
                })
                .toList();
    }

    private KnowledgeBase requireKnowledgeBase(Long id) {
        KnowledgeBase entity = id == null ? null : knowledgeBaseMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("知识库不存在");
        }
        return entity;
    }

    private KbDocument requireDocument(Long id) {
        KbDocument entity = id == null ? null : documentMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("文档不存在");
        }
        return entity;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private KbDocumentVO toVO(KbDocument entity) {
        KbDocumentVO vo = new KbDocumentVO();
        vo.setId(entity.getId());
        vo.setKbId(entity.getKbId());
        vo.setFileName(entity.getFileName());
        vo.setFileUrl(entity.getFileUrl());
        vo.setFileType(entity.getFileType());
        vo.setFileSize(entity.getFileSize());
        vo.setChunkSize(entity.getChunkSize());
        vo.setChunkOverlap(entity.getChunkOverlap());
        vo.setStatus(entity.getStatus());
        vo.setErrorMsg(entity.getErrorMsg());
        vo.setVectorCount(entity.getVectorCount());
        vo.setChunkCount(entity.getChunkCount());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}

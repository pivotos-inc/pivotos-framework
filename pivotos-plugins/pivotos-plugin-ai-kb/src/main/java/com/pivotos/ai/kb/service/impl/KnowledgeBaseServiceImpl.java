package com.pivotos.ai.kb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.domain.dto.KbBaseSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbBaseUpdateRequest;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
import com.pivotos.ai.kb.enums.KbVectorStoreTypeEnum;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.retriever.RrfFusion;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.ai.kb.service.KnowledgeBaseService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageQuery;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识库管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final long PLATFORM_TENANT_ID = 0L;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper documentMapper;
    private final KbPipelineService pipelineService;

    @Override
    public PageResult<KnowledgeBaseVO> page(PageQuery query) {
        Page<KnowledgeBase> page = knowledgeBaseMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .orderByDesc(KnowledgeBase::getCreateTime));
        List<KnowledgeBaseVO> list = page.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public KnowledgeBaseVO get(Long id) {
        KnowledgeBase entity = requireKnowledgeBase(id);
        return toVO(entity);
    }

    @Override
    public Long create(KbBaseSaveRequest request) {
        validateVectorStoreType(request.getVectorStoreType());
        KnowledgeBase entity = new KnowledgeBase();
        fillEntity(entity, request);
        entity.setTenantId(currentTenantId());
        knowledgeBaseMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void update(KbBaseUpdateRequest request) {
        validateVectorStoreType(request.getVectorStoreType());
        KnowledgeBase entity = requireKnowledgeBase(request.getId());
        fillEntity(entity, request);
        knowledgeBaseMapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KnowledgeBase entity = requireKnowledgeBase(id);
        // 先清向量
        pipelineService.deleteByKb(entity);
        // 逻辑删除文档记录
        documentMapper.delete(Wrappers.<KbDocument>lambdaQuery().eq(KbDocument::getKbId, id));
        // 逻辑删除知识库
        knowledgeBaseMapper.deleteById(id);
    }

    @Override
    public List<KnowledgeBaseVO> listSimple() {
        return knowledgeBaseMapper.selectList(
                        Wrappers.<KnowledgeBase>lambdaQuery()
                                .eq(KnowledgeBase::getStatus, 0)
                                .orderByDesc(KnowledgeBase::getCreateTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private KnowledgeBase requireKnowledgeBase(Long id) {
        KnowledgeBase entity = id == null ? null : knowledgeBaseMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("知识库不存在");
        }
        return entity;
    }

    private void fillEntity(KnowledgeBase entity, KbBaseSaveRequest request) {
        entity.setName(request.getName().strip());
        entity.setDescription(request.getDescription());
        entity.setVectorStoreType(request.getVectorStoreType().toLowerCase());
        entity.setEmbeddingModel(StringUtils.hasText(request.getEmbeddingModel())
                ? request.getEmbeddingModel().strip() : null);
        entity.setChunkSize(request.getChunkSize());
        entity.setChunkOverlap(request.getChunkOverlap());
        entity.setHybridSearch(request.getHybridSearch());
        entity.setStatus(request.getStatus());
    }

    private void validateVectorStoreType(String type) {
        if (!StringUtils.hasText(type) || KbVectorStoreTypeEnum.of(type) == null) {
            throw new ServiceException("不支持的向量存储类型: " + type);
        }
    }

    private KnowledgeBaseVO toVO(KnowledgeBase entity) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setVectorStoreType(entity.getVectorStoreType());
        vo.setEmbeddingModel(entity.getEmbeddingModel());
        vo.setChunkSize(entity.getChunkSize());
        vo.setChunkOverlap(entity.getChunkOverlap());
        vo.setHybridSearch(entity.getHybridSearch());
        vo.setStatus(entity.getStatus());
        vo.setTenantId(entity.getTenantId());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setDocCount(documentMapper.selectCount(
                Wrappers.<KbDocument>lambdaQuery().eq(KbDocument::getKbId, entity.getId())));
        return vo;
    }

    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    @Override
    public List<KbSearchResultDTO> search(Long kbId, String query, int topK) {
        KnowledgeBase entity = requireKnowledgeBase(kbId);
        return pipelineService.search(entity, query, topK).stream()
                .map(this::toSearchResultDTO)
                .toList();
    }

    private KbSearchResultDTO toSearchResultDTO(RrfFusion.FusedResult result) {
        KbSearchResultDTO dto = new KbSearchResultDTO();
        dto.setContent(result.content());
        dto.setScore(result.score());
        if (result.metadata() != null) {
            String fileName = (String) result.metadata().get("file_name");
            dto.setFileName(StringUtils.hasText(fileName) ? fileName : null);
        }
        return dto;
    }
}

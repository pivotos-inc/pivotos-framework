package com.pivotos.ai.kb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.api.enums.KbType;
import com.pivotos.ai.kb.domain.dto.KbBaseSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbBaseUpdateRequest;
import com.pivotos.ai.kb.domain.entity.AiKbChunk;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
import com.pivotos.ai.kb.enums.KbVectorStoreTypeEnum;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final long PLATFORM_TENANT_ID = 0L;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper documentMapper;
    private final AiKbChunkMapper chunkMapper;
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
        entity.setKbType(normalizeKbType(request.getKbType()));
        entity.setDescription(request.getDescription());
        entity.setVectorStoreType(request.getVectorStoreType().toLowerCase());
        entity.setEmbeddingModel(StringUtils.hasText(request.getEmbeddingModel())
                ? request.getEmbeddingModel().strip() : null);
        entity.setChunkSize(request.getChunkSize());
        entity.setChunkOverlap(request.getChunkOverlap());
        entity.setHybridSearch(request.getHybridSearch());
        entity.setRerank(request.getRerank());
        entity.setQueryRewrite(request.getQueryRewrite());
        entity.setStatus(request.getStatus());
    }

    private void validateVectorStoreType(String type) {
        if (!StringUtils.hasText(type) || KbVectorStoreTypeEnum.of(type) == null) {
            throw new ServiceException("不支持的向量存储类型: " + type);
        }
    }

    /**
     * 知识库类型归一化（A4E / S117）：只认 policy / general 两个枚举值，
     * 空值兜底 general，其余一律拒绝——自由文本会让「是否制度类」退化成字符串匹配。
     */
    private String normalizeKbType(String value) {
        if (!StringUtils.hasText(value)) {
            return KbType.GENERAL;
        }
        String trimmed = value.strip().toLowerCase();
        if (KbType.POLICY.equals(trimmed) || KbType.GENERAL.equals(trimmed)) {
            return trimmed;
        }
        throw new ServiceException("不支持的知识库类型: " + value);
    }

    private KnowledgeBaseVO toVO(KnowledgeBase entity) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setKbType(entity.getKbType());
        vo.setDescription(entity.getDescription());
        vo.setVectorStoreType(entity.getVectorStoreType());
        vo.setEmbeddingModel(entity.getEmbeddingModel());
        vo.setChunkSize(entity.getChunkSize());
        vo.setChunkOverlap(entity.getChunkOverlap());
        vo.setHybridSearch(entity.getHybridSearch());
        vo.setRerank(entity.getRerank());
        vo.setQueryRewrite(entity.getQueryRewrite());
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
        List<RrfFusion.FusedResult> results = pipelineService.search(entity, query, topK);
        List<KbSearchResultDTO> dtos = new ArrayList<>(results.size());
        for (RrfFusion.FusedResult result : results) {
            dtos.add(toSearchResultDTO(result, kbId));
        }
        fillChunkRefs(kbId, results, dtos);
        return dtos;
    }

    private KbSearchResultDTO toSearchResultDTO(RrfFusion.FusedResult result, Long kbId) {
        KbSearchResultDTO dto = new KbSearchResultDTO();
        dto.setContent(result.content());
        dto.setScore(result.score());
        dto.setVectorRank(result.vectorRank());
        dto.setBm25Rank(result.bm25Rank());
        dto.setRerankScore(result.rerankScore());
        dto.setKbId(kbId);
        if (result.metadata() != null) {
            String fileName = (String) result.metadata().get("file_name");
            dto.setFileName(StringUtils.hasText(fileName) ? fileName : null);
        }
        // BM25 通道命中直接携带分块实体（S68 溯源）
        AiKbChunk chunk = result.chunk();
        if (chunk != null) {
            dto.setChunkId(chunk.getId());
            dto.setDocId(chunk.getDocId());
        }
        return dto;
    }

    /**
     * 补齐纯向量通道结果的 chunkId/docId（S68 溯源下钻）：
     * 按 content_hash（内容前 100 字 md5，与索引写入口径一致）批量反查 ai_kb_chunk，
     * 反查失败静默置 null，不影响检索结果本身。
     */
    private void fillChunkRefs(Long kbId, List<RrfFusion.FusedResult> results, List<KbSearchResultDTO> dtos) {
        List<Integer> missingIdx = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < dtos.size(); i++) {
            KbSearchResultDTO dto = dtos.get(i);
            if (dto.getChunkId() == null && StringUtils.hasText(results.get(i).content())) {
                missingIdx.add(i);
                hashes.add(contentHashOf(results.get(i).content()));
            }
        }
        if (missingIdx.isEmpty()) {
            return;
        }
        try {
            List<AiKbChunk> chunks = chunkMapper.selectList(Wrappers.<AiKbChunk>lambdaQuery()
                    .select(AiKbChunk::getId, AiKbChunk::getDocId, AiKbChunk::getContentHash)
                    .eq(AiKbChunk::getKbId, kbId)
                    .in(AiKbChunk::getContentHash, hashes));
            Map<String, AiKbChunk> byHash = new HashMap<>();
            for (AiKbChunk chunk : chunks) {
                byHash.putIfAbsent(chunk.getContentHash(), chunk);
            }
            for (int j = 0; j < missingIdx.size(); j++) {
                AiKbChunk hit = byHash.get(hashes.get(j));
                if (hit != null) {
                    dtos.get(missingIdx.get(j)).setChunkId(hit.getId());
                    dtos.get(missingIdx.get(j)).setDocId(hit.getDocId());
                }
            }
        } catch (Exception e) {
            log.warn("[PivotOS-KB] 分块溯源反查失败，chunkId 置空: kbId={}, reason={}", kbId, e.getMessage());
        }
    }

    /** 内容哈希口径与 KbPipelineService 索引写入一致：前 100 字 md5 */
    private String contentHashOf(String content) {
        String prefix = content.length() > 100 ? content.substring(0, 100) : content;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(prefix.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(prefix.hashCode());
        }
    }
}

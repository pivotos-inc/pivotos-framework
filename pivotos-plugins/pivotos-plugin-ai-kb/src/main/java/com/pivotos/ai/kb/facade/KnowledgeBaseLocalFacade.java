package com.pivotos.ai.kb.facade;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.api.dto.KbStatsDTO;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.mapper.KbEvalRecordMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库门面本地实现（单体部署：对话 Plugin 通过此实现调用知识库检索）。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseLocalFacade implements IKnowledgeBaseFacade {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper documentMapper;
    private final AiKbChunkMapper chunkMapper;
    private final KbEvalRecordMapper evalRecordMapper;

    @Override
    public List<KbOptionDTO> listOptions() {
        return knowledgeBaseService.listSimple().stream()
                .map(this::toOptionDTO)
                .toList();
    }

    @Override
    public List<KbSearchResultDTO> search(Long kbId, String query, int topK) {
        return knowledgeBaseService.search(kbId, query, topK);
    }

    @Override
    public KbStatsDTO kbStats() {
        KbStatsDTO dto = new KbStatsDTO();
        dto.setBaseCount(knowledgeBaseMapper.selectCount(null));
        dto.setDocumentCount(documentMapper.selectCount(null));
        // 分块数按文档 chunk_count 汇总（V1.2.31 落库口径，避免删除残留干扰）
        QueryWrapper<KbDocument> wrapper = new QueryWrapper<>();
        wrapper.select("COALESCE(SUM(chunk_count), 0)");
        List<Object> sums = documentMapper.selectObjs(wrapper);
        dto.setChunkCount(sums.isEmpty() || sums.get(0) == null ? 0L : ((Number) sums.get(0)).longValue());
        dto.setEvalRecordCount(evalRecordMapper.selectCount(null));
        return dto;
    }

    private KbOptionDTO toOptionDTO(KnowledgeBaseVO vo) {
        KbOptionDTO dto = new KbOptionDTO();
        dto.setId(vo.getId());
        dto.setName(vo.getName());
        dto.setKbType(vo.getKbType());
        dto.setQueryRewrite(vo.getQueryRewrite());
        return dto;
    }
}

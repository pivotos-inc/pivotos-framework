package com.pivotos.ai.kb.facade;

import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
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

    private KbOptionDTO toOptionDTO(KnowledgeBaseVO vo) {
        KbOptionDTO dto = new KbOptionDTO();
        dto.setId(vo.getId());
        dto.setName(vo.getName());
        dto.setQueryRewrite(vo.getQueryRewrite());
        return dto;
    }
}

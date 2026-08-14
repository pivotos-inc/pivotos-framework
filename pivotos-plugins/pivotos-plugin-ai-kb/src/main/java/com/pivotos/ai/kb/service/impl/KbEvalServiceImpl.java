package com.pivotos.ai.kb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.kb.domain.dto.KbEvalSaveRequest;
import com.pivotos.ai.kb.domain.entity.KbEvalQuestion;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.KbEvalCompareVO;
import com.pivotos.ai.kb.domain.vo.KbEvalQuestionVO;
import com.pivotos.ai.kb.mapper.KbEvalQuestionMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.retriever.RrfFusion;
import com.pivotos.ai.kb.service.KbEvalService;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 知识库检索评测服务实现（S66）。
 */
@Service
@RequiredArgsConstructor
public class KbEvalServiceImpl implements KbEvalService {

    private static final long PLATFORM_TENANT_ID = 0L;

    private final KbEvalQuestionMapper evalQuestionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbPipelineService pipelineService;

    @Override
    public List<KbEvalQuestionVO> listByKb(Long kbId) {
        return evalQuestionMapper.selectList(
                        Wrappers.<KbEvalQuestion>lambdaQuery()
                                .eq(KbEvalQuestion::getKbId, kbId)
                                .orderByAsc(KbEvalQuestion::getSort)
                                .orderByAsc(KbEvalQuestion::getCreateTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public Long create(KbEvalSaveRequest request) {
        requireKnowledgeBase(request.getKbId());
        KbEvalQuestion entity = new KbEvalQuestion();
        fillEntity(entity, request);
        entity.setTenantId(currentTenantId());
        evalQuestionMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void update(KbEvalSaveRequest request) {
        KbEvalQuestion entity = requireQuestion(request.getId());
        fillEntity(entity, request);
        evalQuestionMapper.updateById(entity);
    }

    @Override
    public void delete(Long id) {
        requireQuestion(id);
        evalQuestionMapper.deleteById(id);
    }

    @Override
    public KbEvalCompareVO runOne(Long questionId, int topK) {
        KbEvalQuestion question = requireQuestion(questionId);
        KnowledgeBase kb = requireKnowledgeBase(question.getKbId());

        List<RrfFusion.FusedResult> baseline = pipelineService.search(kb, question.getQuestion(), topK, false);
        List<RrfFusion.FusedResult> reranked = pipelineService.search(kb, question.getQuestion(), topK, true);

        String keyword = question.getExpectedKeyword().toLowerCase(Locale.ROOT);
        int baselineRank = hitRank(baseline, keyword);
        int rerankRank = hitRank(reranked, keyword);

        KbEvalCompareVO vo = new KbEvalCompareVO();
        vo.setQuestionId(question.getId());
        vo.setQuestion(question.getQuestion());
        vo.setExpectedKeyword(question.getExpectedKeyword());
        vo.setBaselineHit(baselineRank > 0);
        vo.setBaselineRank(baselineRank);
        vo.setRerankHit(rerankRank > 0);
        vo.setRerankRank(rerankRank);
        vo.setOrderChanged(!contentSequence(baseline).equals(contentSequence(reranked)));
        return vo;
    }

    /** 首次命中排名（1-based，0=未命中）：topK 任一结果内容包含预期关键词（忽略大小写） */
    private int hitRank(List<RrfFusion.FusedResult> results, String keywordLower) {
        for (int i = 0; i < results.size(); i++) {
            String content = results.get(i).content();
            if (content != null && content.toLowerCase(Locale.ROOT).contains(keywordLower)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** topK 内容序列（用于对比两配置结果是否改序/换题） */
    private List<String> contentSequence(List<RrfFusion.FusedResult> results) {
        return results.stream().map(RrfFusion.FusedResult::content).toList();
    }

    private KbEvalQuestion requireQuestion(Long id) {
        KbEvalQuestion entity = id == null ? null : evalQuestionMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("评测问题不存在");
        }
        return entity;
    }

    private KnowledgeBase requireKnowledgeBase(Long id) {
        KnowledgeBase entity = id == null ? null : knowledgeBaseMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("知识库不存在");
        }
        return entity;
    }

    private void fillEntity(KbEvalQuestion entity, KbEvalSaveRequest request) {
        entity.setKbId(request.getKbId());
        entity.setQuestion(request.getQuestion().strip());
        entity.setExpectedKeyword(request.getExpectedKeyword().strip());
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
    }

    private KbEvalQuestionVO toVO(KbEvalQuestion entity) {
        KbEvalQuestionVO vo = new KbEvalQuestionVO();
        vo.setId(entity.getId());
        vo.setKbId(entity.getKbId());
        vo.setQuestion(entity.getQuestion());
        vo.setExpectedKeyword(entity.getExpectedKeyword());
        vo.setSort(entity.getSort());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }
}

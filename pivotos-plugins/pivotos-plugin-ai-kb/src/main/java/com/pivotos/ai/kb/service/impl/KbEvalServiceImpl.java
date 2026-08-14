package com.pivotos.ai.kb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.kb.domain.dto.KbEvalRecordSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbEvalSaveRequest;
import com.pivotos.ai.kb.domain.entity.KbEvalQuestion;
import com.pivotos.ai.kb.domain.entity.KbEvalRecord;
import com.pivotos.ai.kb.domain.entity.KbEvalRecordItem;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.KbEvalCompareVO;
import com.pivotos.ai.kb.domain.vo.KbEvalQuestionVO;
import com.pivotos.ai.kb.domain.vo.KbEvalRecordItemVO;
import com.pivotos.ai.kb.domain.vo.KbEvalRecordVO;
import com.pivotos.ai.kb.mapper.KbEvalQuestionMapper;
import com.pivotos.ai.kb.mapper.KbEvalRecordItemMapper;
import com.pivotos.ai.kb.mapper.KbEvalRecordMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.retriever.RrfFusion;
import com.pivotos.ai.kb.service.KbEvalService;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * 知识库检索评测服务实现（S66/S67）。
 */
@Service
@RequiredArgsConstructor
public class KbEvalServiceImpl implements KbEvalService {

    private static final long PLATFORM_TENANT_ID = 0L;

    /** 历史跑分列表上限（不分页） */
    private static final int RECORD_LIST_LIMIT = 20;

    private final KbEvalQuestionMapper evalQuestionMapper;
    private final KbEvalRecordMapper evalRecordMapper;
    private final KbEvalRecordItemMapper evalRecordItemMapper;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveRun(KbEvalRecordSaveRequest request) {
        requireKnowledgeBase(request.getKbId());
        List<KbEvalRecordSaveRequest.Item> items = request.getItems();
        int total = items.size();

        long tenantId = currentTenantId();
        KbEvalRecord record = new KbEvalRecord();
        record.setKbId(request.getKbId());
        record.setQuestionCount(total);
        record.setBaselineHit((int) items.stream().filter(i -> i.getBaselineRank() > 0).count());
        record.setRerankHit((int) items.stream().filter(i -> i.getRerankRank() > 0).count());
        record.setBaselineHitRate(rate(record.getBaselineHit(), total));
        record.setRerankHitRate(rate(record.getRerankHit(), total));
        record.setBaselineMrr(mrr(items.stream().map(KbEvalRecordSaveRequest.Item::getBaselineRank).toList(), total));
        record.setRerankMrr(mrr(items.stream().map(KbEvalRecordSaveRequest.Item::getRerankRank).toList(), total));
        record.setOrderChangedCount((int) items.stream().filter(i -> Boolean.TRUE.equals(i.getOrderChanged())).count());
        record.setTenantId(tenantId);
        evalRecordMapper.insert(record);

        for (KbEvalRecordSaveRequest.Item item : items) {
            KbEvalRecordItem entity = new KbEvalRecordItem();
            entity.setRecordId(record.getId());
            entity.setKbId(request.getKbId());
            entity.setQuestionId(item.getQuestionId());
            entity.setQuestion(item.getQuestion().strip());
            entity.setExpectedKeyword(item.getExpectedKeyword().strip());
            entity.setBaselineRank(item.getBaselineRank());
            entity.setRerankRank(item.getRerankRank());
            entity.setOrderChanged(Boolean.TRUE.equals(item.getOrderChanged()));
            entity.setTenantId(tenantId);
            evalRecordItemMapper.insert(entity);
        }
        return record.getId();
    }

    @Override
    public List<KbEvalRecordVO> listRecords(Long kbId) {
        return evalRecordMapper.selectList(
                        Wrappers.<KbEvalRecord>lambdaQuery()
                                .eq(KbEvalRecord::getKbId, kbId)
                                .orderByDesc(KbEvalRecord::getCreateTime)
                                .last("LIMIT " + RECORD_LIST_LIMIT))
                .stream()
                .map(this::toRecordVO)
                .toList();
    }

    @Override
    public List<KbEvalRecordItemVO> getRecordDetail(Long recordId) {
        requireRecord(recordId);
        return evalRecordItemMapper.selectList(
                        Wrappers.<KbEvalRecordItem>lambdaQuery()
                                .eq(KbEvalRecordItem::getRecordId, recordId)
                                .orderByAsc(KbEvalRecordItem::getId))
                .stream()
                .map(this::toRecordItemVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecord(Long recordId) {
        requireRecord(recordId);
        evalRecordItemMapper.delete(
                Wrappers.<KbEvalRecordItem>lambdaQuery().eq(KbEvalRecordItem::getRecordId, recordId));
        evalRecordMapper.deleteById(recordId);
    }

    /** Hit@K：命中数/题数（scale 4） */
    private BigDecimal rate(int hit, int total) {
        return BigDecimal.valueOf(hit).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /** MRR：avg(1/rank)，未命中（rank=0）计 0（scale 4） */
    private BigDecimal mrr(List<Integer> ranks, int total) {
        double sum = ranks.stream().filter(r -> r > 0).mapToDouble(r -> 1.0 / r).sum();
        return BigDecimal.valueOf(sum / total).setScale(4, RoundingMode.HALF_UP);
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

    private KbEvalRecord requireRecord(Long id) {
        KbEvalRecord entity = id == null ? null : evalRecordMapper.selectById(id);
        if (entity == null) {
            throw new ServiceException("跑分记录不存在");
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

    private KbEvalRecordVO toRecordVO(KbEvalRecord entity) {
        KbEvalRecordVO vo = new KbEvalRecordVO();
        vo.setId(entity.getId());
        vo.setKbId(entity.getKbId());
        vo.setQuestionCount(entity.getQuestionCount());
        vo.setBaselineHit(entity.getBaselineHit());
        vo.setRerankHit(entity.getRerankHit());
        vo.setBaselineHitRate(entity.getBaselineHitRate());
        vo.setRerankHitRate(entity.getRerankHitRate());
        vo.setBaselineMrr(entity.getBaselineMrr());
        vo.setRerankMrr(entity.getRerankMrr());
        vo.setOrderChangedCount(entity.getOrderChangedCount());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private KbEvalRecordItemVO toRecordItemVO(KbEvalRecordItem entity) {
        KbEvalRecordItemVO vo = new KbEvalRecordItemVO();
        vo.setId(entity.getId());
        vo.setRecordId(entity.getRecordId());
        vo.setQuestionId(entity.getQuestionId());
        vo.setQuestion(entity.getQuestion());
        vo.setExpectedKeyword(entity.getExpectedKeyword());
        vo.setBaselineRank(entity.getBaselineRank());
        vo.setRerankRank(entity.getRerankRank());
        vo.setOrderChanged(entity.getOrderChanged());
        return vo;
    }

    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }
}

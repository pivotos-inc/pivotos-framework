package com.pivotos.ai.kb.service;

import com.pivotos.ai.kb.domain.dto.KbEvalRecordSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbEvalSaveRequest;
import com.pivotos.ai.kb.domain.vo.KbEvalCompareVO;
import com.pivotos.ai.kb.domain.vo.KbEvalQuestionVO;
import com.pivotos.ai.kb.domain.vo.KbEvalRecordItemVO;
import com.pivotos.ai.kb.domain.vo.KbEvalRecordVO;

import java.util.List;

/**
 * 知识库检索评测服务（S66/S67）：评测问题集管理 + 单题「rerank 关/开」对比跑分 + 跑分记录落库。
 */
public interface KbEvalService {

    /**
     * 按知识库查询评测问题集（按 sort / 创建时间升序）
     */
    List<KbEvalQuestionVO> listByKb(Long kbId);

    /**
     * 新增评测问题
     */
    Long create(KbEvalSaveRequest request);

    /**
     * 修改评测问题
     */
    void update(KbEvalSaveRequest request);

    /**
     * 删除评测问题
     */
    void delete(Long id);

    /**
     * 单题跑分：对同一问题先后执行「rerank 关（基线）/ rerank 开」两次检索，
     * 以预期关键词是否命中 topK 判定召回质量。
     *
     * @param questionId 评测问题ID
     * @param topK       召回数量
     * @return 双配置对比结果
     */
    KbEvalCompareVO runOne(Long questionId, int topK);

    /**
     * 保存一轮全量跑分记录（S67）：聚合指标（Hit@K / MRR）由后端统一计算，
     * 逐题结果以快照明细落库。
     *
     * @param request 跑分结果（kbId + 逐题明细）
     * @return 跑分记录ID
     */
    Long saveRun(KbEvalRecordSaveRequest request);

    /**
     * 按知识库查询最近跑分记录（最近 20 条，跑分时间倒序）
     */
    List<KbEvalRecordVO> listRecords(Long kbId);

    /**
     * 查询某轮跑分的逐题明细（快照）
     */
    List<KbEvalRecordItemVO> getRecordDetail(Long recordId);

    /**
     * 删除跑分记录（级联删除逐题明细）
     */
    void deleteRecord(Long recordId);
}

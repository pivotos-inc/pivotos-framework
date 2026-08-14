package com.pivotos.ai.kb.service;

import com.pivotos.ai.kb.domain.dto.KbEvalSaveRequest;
import com.pivotos.ai.kb.domain.vo.KbEvalCompareVO;
import com.pivotos.ai.kb.domain.vo.KbEvalQuestionVO;

import java.util.List;

/**
 * 知识库检索评测服务（S66）：评测问题集管理 + 单题「rerank 关/开」对比跑分。
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
}

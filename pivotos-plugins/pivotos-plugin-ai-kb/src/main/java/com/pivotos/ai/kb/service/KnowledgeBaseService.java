package com.pivotos.ai.kb.service;

import com.pivotos.ai.kb.domain.dto.KbBaseSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbBaseUpdateRequest;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
import com.pivotos.common.core.page.PageQuery;
import com.pivotos.common.core.page.PageResult;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识库管理服务。
 */
public interface KnowledgeBaseService {

    /**
     * 分页查询知识库
     */
    PageResult<KnowledgeBaseVO> page(PageQuery query);

    /**
     * 知识库详情
     */
    KnowledgeBaseVO get(Long id);

    /**
     * 新增知识库
     */
    Long create(KbBaseSaveRequest request);

    /**
     * 修改知识库
     */
    void update(KbBaseUpdateRequest request);

    /**
     * 删除知识库（级联清向量、删文档）
     */
    void delete(Long id);

    /**
     * 下拉选择：列出启用的知识库
     */
    List<KnowledgeBaseVO> listSimple();

    /**
     * 在指定知识库中检索相似文本块
     */
    List<Document> search(Long kbId, String query, int topK);
}

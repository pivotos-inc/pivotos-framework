package com.pivotos.ai.kb.service;

import com.pivotos.ai.kb.domain.dto.KbDocPageQuery;
import com.pivotos.ai.kb.domain.dto.KbDocUploadRequest;
import com.pivotos.ai.kb.domain.vo.AiKbChunkVO;
import com.pivotos.ai.kb.domain.vo.KbDocumentVO;
import com.pivotos.common.core.page.PageResult;

import java.util.List;

/**
 * 知识库文档管理服务。
 */
public interface KbDocumentService {

    /**
     * 分页查询文档
     */
    PageResult<KbDocumentVO> page(KbDocPageQuery query);

    /**
     * 文档详情
     */
    KbDocumentVO get(Long id);

    /**
     * 上传文档并触发向量化
     */
    Long upload(KbDocUploadRequest request);

    /**
     * 删除文档并清向量
     */
    void delete(Long id);

    /**
     * 重新向量化
     */
    void reindex(Long id);

    /**
     * 查询文档文本块列表（按块序号升序，分块查看/解析预览用）
     */
    List<AiKbChunkVO> listChunks(Long id);
}

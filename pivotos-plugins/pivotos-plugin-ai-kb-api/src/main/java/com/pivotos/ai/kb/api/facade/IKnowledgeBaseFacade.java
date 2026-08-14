package com.pivotos.ai.kb.api.facade;

import com.pivotos.ai.kb.api.dto.KbOptionDTO;
import com.pivotos.ai.kb.api.dto.KbSearchResultDTO;

import java.util.List;

/**
 * 知识库门面契约（跨 Plugin 检索知识库的唯一入口，禁跨 Plugin 直接依赖实现模块）
 *
 * <p>对话 Plugin 在 RAG 对话场景通过此契约调用知识库检索；
 * 单体部署由 ai-kb 插件提供本地实现，微服务形态可替换为远程实现。
 */
public interface IKnowledgeBaseFacade {

    /**
     * 列出启用的知识库（对话页下拉选择用）
     */
    List<KbOptionDTO> listOptions();

    /**
     * 在指定知识库中检索相似文本块
     *
     * @param kbId  知识库 ID
     * @param query 查询文本
     * @param topK  返回条数
     * @return 检索结果列表
     */
    List<KbSearchResultDTO> search(Long kbId, String query, int topK);
}

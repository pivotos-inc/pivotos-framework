package com.pivotos.mind.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.mind.api.dto.KnowledgeQuery;
import com.pivotos.mind.api.dto.KnowledgeSaveBody;
import com.pivotos.mind.domain.entity.MindKnowledge;
import com.pivotos.mind.api.vo.KnowledgeVO;

import java.util.List;

/** 智域知识库服务 */
public interface MindKnowledgeService extends IService<MindKnowledge> {

    /** 分页列表 */
    PageResult<KnowledgeVO> pageKnowledge(Long userId, KnowledgeQuery query);

    /** 最近知识（首页用） */
    List<KnowledgeVO> recentKnowledge(Long userId, int limit);

    /** 详情 */
    KnowledgeVO getKnowledge(Long userId, Long id);

    /** 创建 */
    Long createKnowledge(Long userId, KnowledgeSaveBody body);

    /** 修改 */
    void updateKnowledge(Long userId, Long id, KnowledgeSaveBody body);

    /** 删除 */
    void deleteKnowledge(Long userId, Long id);

    /** 统计 */
    Long countByUser(Long userId);
}

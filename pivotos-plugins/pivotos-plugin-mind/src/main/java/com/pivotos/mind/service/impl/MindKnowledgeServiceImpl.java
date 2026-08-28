package com.pivotos.mind.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.mind.api.dto.KnowledgeQuery;
import com.pivotos.mind.api.dto.KnowledgeSaveBody;
import com.pivotos.mind.api.enums.MindErrorCode;
import com.pivotos.mind.domain.entity.MindKnowledge;
import com.pivotos.mind.api.vo.KnowledgeVO;
import com.pivotos.mind.mapper.MindKnowledgeMapper;
import com.pivotos.mind.service.MindKnowledgeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 智域知识库服务实现 */
@Service
public class MindKnowledgeServiceImpl extends ServiceImpl<MindKnowledgeMapper, MindKnowledge>
        implements MindKnowledgeService {

    @Override
    public PageResult<KnowledgeVO> pageKnowledge(Long userId, KnowledgeQuery query) {
        Page<MindKnowledge> page = page(new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<MindKnowledge>lambdaQuery()
                        .eq(MindKnowledge::getUserId, userId)
                        .eq(query.getType() != null && !query.getType().isBlank(), MindKnowledge::getType, query.getType())
                        .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w -> w.like(MindKnowledge::getTitle, query.getKeyword())
                                        .or()
                                        .like(MindKnowledge::getContent, query.getKeyword()))
                        .orderByDesc(MindKnowledge::getCreateTime));
        List<KnowledgeVO> rows = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(rows, page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public List<KnowledgeVO> recentKnowledge(Long userId, int limit) {
        return list(Wrappers.<MindKnowledge>lambdaQuery()
                .eq(MindKnowledge::getUserId, userId)
                .orderByDesc(MindKnowledge::getCreateTime)
                .last("LIMIT " + Math.min(limit, 50)))
                .stream().map(this::toVo).toList();
    }

    @Override
    public KnowledgeVO getKnowledge(Long userId, Long id) {
        MindKnowledge entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        return toVo(entity);
    }

    @Override
    public Long createKnowledge(Long userId, KnowledgeSaveBody body) {
        MindKnowledge entity = new MindKnowledge();
        entity.setUserId(userId);
        entity.setTitle(body.getTitle());
        entity.setType(body.getType());
        entity.setContent(body.getContent());
        entity.setSourceUrl(body.getSourceUrl());
        entity.setTags(body.getTags());
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateKnowledge(Long userId, Long id, KnowledgeSaveBody body) {
        MindKnowledge entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        entity.setTitle(body.getTitle());
        entity.setType(body.getType());
        entity.setContent(body.getContent());
        entity.setSourceUrl(body.getSourceUrl());
        entity.setTags(body.getTags());
        updateById(entity);
    }

    @Override
    public void deleteKnowledge(Long userId, Long id) {
        MindKnowledge entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        removeById(id);
    }

    @Override
    public Long countByUser(Long userId) {
        return count(Wrappers.<MindKnowledge>lambdaQuery().eq(MindKnowledge::getUserId, userId));
    }

    private KnowledgeVO toVo(MindKnowledge entity) {
        KnowledgeVO vo = new KnowledgeVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setType(entity.getType());
        vo.setContent(entity.getContent());
        vo.setSourceUrl(entity.getSourceUrl());
        vo.setTags(entity.getTags());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}

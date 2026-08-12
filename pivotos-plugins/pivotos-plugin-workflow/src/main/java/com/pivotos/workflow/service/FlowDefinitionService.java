package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.workflow.domain.dto.FlowDefinitionQuery;
import com.pivotos.workflow.domain.vo.FlowDefinitionVO;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.utils.page.Page;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 流程定义管理服务：封装 WarmFlow DefService，适配 PivotOS 响应体。
 * <p>
 * 流程定义的新增/编辑/删除由 WarmFlow 内置设计器 UI 处理（/warm-flow-ui/**），
 * 本 Service 只提供：分页列表 + 详情 + 发布 + 挂起/激活 + 删除。
 */
@Service
@RequiredArgsConstructor
public class FlowDefinitionService {

    private final DefService defService;

    /**
     * 分页查询流程定义
     */
    public PageResult<FlowDefinitionVO> pageDefinitions(FlowDefinitionQuery query) {
        // WarmFlow 的 page(entity, page) 以 entity 非空字段做条件过滤
        FlowDefinition condition = new FlowDefinition();
        if (StringUtils.hasText(query.getFlowName())) {
            condition.setFlowName(query.getFlowName());
        }
        if (StringUtils.hasText(query.getFlowCode())) {
            condition.setFlowCode(query.getFlowCode());
        }
        if (StringUtils.hasText(query.getCategory())) {
            condition.setCategory(query.getCategory());
        }
        if (query.getIsPublish() != null) {
            condition.setIsPublish(query.getIsPublish());
        }
        Page<Definition> page = new Page<>(query.getPageNum(), query.getPageSize());
        // 默认按创建时间倒序
        page.setOrderBy("create_time");
        page.setIsAsc("desc");
        defService.page(condition, page);
        List<FlowDefinitionVO> list = page.getList().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 查询流程定义详情
     */
    public FlowDefinitionVO getDefinition(Long id) {
        Definition def = defService.getById(id);
        if (def == null) {
            throw new ServiceException("流程定义不存在");
        }
        return toVO(def);
    }

    /**
     * 发布流程定义
     */
    public void publish(Long id) {
        defService.publish(id);
    }

    /**
     * 挂起/激活流程定义（切换 activity_status）
     */
    public void toggleActivity(Long id) {
        Definition def = defService.getById(id);
        if (def == null) {
            throw new ServiceException("流程定义不存在");
        }
        if (def.getActivityStatus() == 1) {
            defService.unActive(id);
        } else {
            defService.active(id);
        }
    }

    /**
     * 删除流程定义（WarmFlow 逻辑删除）
     */
    public void delete(Long id) {
        defService.removeDef(List.of(id));
    }

    private FlowDefinitionVO toVO(Definition def) {
        FlowDefinitionVO vo = new FlowDefinitionVO();
        vo.setId(def.getId());
        vo.setFlowCode(def.getFlowCode());
        vo.setFlowName(def.getFlowName());
        vo.setModelValue(def.getModelValue());
        vo.setCategory(def.getCategory());
        vo.setVersion(def.getVersion());
        vo.setIsPublish(def.getIsPublish());
        vo.setActivityStatus(def.getActivityStatus());
        vo.setCreateTime(toLocalDateTime(def.getCreateTime()));
        vo.setUpdateTime(toLocalDateTime(def.getUpdateTime()));
        return vo;
    }

    /** java.util.Date → java.time.LocalDateTime（VO 统一 LocalDateTime 输出） */
    private java.time.LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }
}

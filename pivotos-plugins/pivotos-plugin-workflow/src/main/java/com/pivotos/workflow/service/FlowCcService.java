package com.pivotos.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import com.pivotos.workflow.domain.dto.CcPageQuery;
import com.pivotos.workflow.domain.entity.FlowCc;
import com.pivotos.workflow.domain.vo.WorkflowCcVO;
import com.pivotos.workflow.mapper.FlowCcMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.service.InsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程抄送服务（S78 F1）：发起时抄送落库 + 抄送我的分页 + 已读标记。
 * <p>
 * warm-flow 引擎无抄送能力，本服务基于自建 flow_cc 表；
 * 发起人昵称经 IUserFacade 解析（ObjectProvider 延迟解析，未装配 system 插件时降级为 userId）。
 */
@Slf4j
@Service
public class FlowCcService {

    private final FlowCcMapper flowCcMapper;

    private final InsService insService;

    private final IUserFacade userFacade;

    public FlowCcService(FlowCcMapper flowCcMapper, InsService insService,
                         ObjectProvider<IUserFacade> userFacadeProvider) {
        this.flowCcMapper = flowCcMapper;
        this.insService = insService;
        this.userFacade = userFacadeProvider.getIfAvailable();
        if (this.userFacade == null) {
            log.warn("[WorkflowCc] IUserFacade 未装配，抄送记录发起人昵称将降级为用户 ID");
        }
    }

    /**
     * 发起抄送落库：去重、排除发起人自己后批量写入。
     *
     * @param instance  已发起的流程实例（flowName 已补齐）
     * @param ccUserIds 抄送收件人（可空）
     * @return 实际落库的收件人 ID 集合（供后续通知复用同口径）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> recordCc(Instance instance, List<Long> ccUserIds) {
        if (ccUserIds == null || ccUserIds.isEmpty()) {
            return List.of();
        }
        Long creatorId = LoginContext.getUserId();
        List<Long> receivers = ccUserIds.stream()
                .filter(id -> id != null && !id.equals(creatorId))
                .distinct()
                .toList();
        if (receivers.isEmpty()) {
            return List.of();
        }
        String creatorName = resolveNickname(creatorId);
        long tenantId = currentTenantId();
        List<FlowCc> records = receivers.stream().map(userId -> {
            FlowCc cc = new FlowCc();
            cc.setInstanceId(instance.getId());
            cc.setUserId(userId);
            cc.setFlowName(instance.getFlowName());
            cc.setCreatorName(creatorName);
            cc.setReadFlag(0);
            // tenant_id NOT NULL：多租户未启用时 TenantContext 为空，自动填充不生效，显式兜底
            cc.setTenantId(tenantId);
            return cc;
        }).toList();
        for (FlowCc record : records) {
            flowCcMapper.insert(record);
        }
        return receivers;
    }

    /**
     * 抄送我的分页（仅本人数据，flowStatus/nodeName 实时从实例补齐）。
     */
    public PageResult<WorkflowCcVO> pageMine(CcPageQuery query) {
        Long userId = LoginContext.getUserId();
        LambdaQueryWrapper<FlowCc> wrapper = new LambdaQueryWrapper<FlowCc>()
                .eq(FlowCc::getUserId, userId)
                .eq(query.getReadFlag() != null, FlowCc::getReadFlag, query.getReadFlag())
                .like(StringUtils.hasText(query.getFlowName()), FlowCc::getFlowName, query.getFlowName())
                .orderByDesc(FlowCc::getCreateTime);
        Page<FlowCc> page = flowCcMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<WorkflowCcVO> list = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 标记已读（仅本人记录可操作，越权统一按不存在处理）。
     */
    public void markRead(Long id) {
        FlowCc cc = flowCcMapper.selectById(id);
        Long userId = LoginContext.getUserId();
        if (cc == null || !cc.getUserId().equals(userId)) {
            throw new ServiceException("抄送记录不存在");
        }
        if (Integer.valueOf(1).equals(cc.getReadFlag())) {
            return;
        }
        FlowCc update = new FlowCc();
        update.setId(id);
        update.setReadFlag(1);
        update.setReadTime(LocalDateTime.now());
        flowCcMapper.updateById(update);
    }

    private WorkflowCcVO toVO(FlowCc cc) {
        WorkflowCcVO vo = new WorkflowCcVO();
        vo.setId(cc.getId());
        vo.setInstanceId(cc.getInstanceId());
        vo.setFlowName(cc.getFlowName());
        vo.setCreatorName(cc.getCreatorName());
        vo.setReadFlag(cc.getReadFlag());
        vo.setReadTime(cc.getReadTime());
        vo.setCreateTime(cc.getCreateTime());
        // 实例状态/当前节点实时补齐（逐条查实例，抄送页小分页可接受）
        try {
            Instance ins = insService.getById(cc.getInstanceId());
            if (ins != null) {
                vo.setFlowStatus(ins.getFlowStatus());
                vo.setNodeName(ins.getNodeName());
            }
        } catch (Exception e) {
            log.warn("[WorkflowCc] 补齐实例状态失败: instanceId={}, error={}", cc.getInstanceId(), e.getMessage());
        }
        return vo;
    }

    /** 当前租户 ID（无租户上下文 = 平台租户 0，与 ai 插件 currentTenantId 口径一致） */
    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    /** 解析用户昵称（Facade 未装配/用户不存在时降级为用户 ID） */
    private String resolveNickname(Long userId) {
        if (userId == null) {
            return null;
        }
        if (userFacade != null) {
            try {
                UserDTO user = userFacade.getById(userId);
                if (user != null && StringUtils.hasText(user.getNickname())) {
                    return user.getNickname();
                }
                if (user != null && StringUtils.hasText(user.getUsername())) {
                    return user.getUsername();
                }
            } catch (Exception e) {
                log.warn("[WorkflowCc] 解析发起人昵称失败: userId={}, error={}", userId, e.getMessage());
            }
        }
        return String.valueOf(userId);
    }
}

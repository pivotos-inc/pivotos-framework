package com.pivotos.workflow.service;

import com.pivotos.workflow.api.IWorkflowFacade;
import com.pivotos.workflow.api.dto.WorkflowStatsDTO;
import com.pivotos.workflow.domain.dto.StartInstanceCmd;
import com.pivotos.workflow.domain.vo.WorkflowInstanceVO;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * IWorkflowFacade 实现：为跨插件调用提供审批能力入口。
 */
@Service
@RequiredArgsConstructor
public class WorkflowFacadeImpl implements IWorkflowFacade {

    private final FlowTaskService flowTaskService;
    private final FlowInstanceService flowInstanceService;
    private final InsService insService;

    /** warmflow flow_status 全量状态码（V1.2.21 建表注释口径） */
    private static final String[] INSTANCE_STATUSES = {"0", "1", "2", "4", "5", "6", "8", "9", "10", "11"};

    @Override
    public long countPending() {
        return flowTaskService.countPending();
    }

    @Override
    public Long startInstance(String flowCode, String businessId, Map<String, Object> variable) {
        StartInstanceCmd cmd = new StartInstanceCmd();
        cmd.setFlowCode(flowCode);
        cmd.setBusinessId(businessId);
        cmd.setVariable(variable);
        WorkflowInstanceVO vo = flowInstanceService.start(cmd);
        return vo.getId();
    }

    @Override
    public WorkflowStatsDTO instanceStats() {
        WorkflowStatsDTO dto = new WorkflowStatsDTO();
        dto.setPendingTasks(flowTaskService.countPending());
        long total = 0L;
        for (String status : INSTANCE_STATUSES) {
            FlowInstance condition = new FlowInstance();
            condition.setFlowStatus(status);
            long count = insService.selectCount(condition);
            total += count;
            if (count > 0) {
                dto.getStatusCounts().put(status, count);
            }
        }
        dto.setTotalInstances(total);
        return dto;
    }
}

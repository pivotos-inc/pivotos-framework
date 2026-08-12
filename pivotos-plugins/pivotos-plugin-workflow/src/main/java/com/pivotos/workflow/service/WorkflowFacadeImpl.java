package com.pivotos.workflow.service;

import com.pivotos.workflow.api.IWorkflowFacade;
import com.pivotos.workflow.domain.dto.StartInstanceCmd;
import com.pivotos.workflow.domain.vo.WorkflowInstanceVO;
import lombok.RequiredArgsConstructor;
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
}

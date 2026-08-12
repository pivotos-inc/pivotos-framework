package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.workflow.domain.dto.StartInstanceCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowInstanceVO;
import com.pivotos.workflow.service.WorkflowNotifyService;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.utils.page.Page;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 流程实例管理服务：发起 / 撤回 / 终止 / 我的发起列表。
 */
@Service
@RequiredArgsConstructor
public class FlowInstanceService {

    private final InsService insService;
    private final TaskService taskService;
    private final DefService defService;
    private final WorkflowNotifyService notifyService;

    /**
     * 发起流程实例
     */
    public WorkflowInstanceVO start(StartInstanceCmd cmd) {
        if (!StringUtils.hasText(cmd.getFlowCode())) {
            throw new ServiceException("流程编码不能为空");
        }
        FlowParams params = FlowParams.build()
                .flowCode(cmd.getFlowCode())
                .handler(currentHandler());
        if (cmd.getVariable() != null) {
            params.variable(cmd.getVariable());
        }
        Instance instance = insService.start(cmd.getFlowCode(), params);
        if (instance == null) {
            throw new ServiceException("流程实例创建失败，请检查流程定义是否已发布");
        }
        // 设置业务 ID（WarmFlow start 不直接接受 businessId，需更新）
        if (StringUtils.hasText(cmd.getBusinessId())) {
            FlowInstance update = new FlowInstance();
            update.setId(instance.getId());
            update.setBusinessId(cmd.getBusinessId());
            insService.updateById(update);
            instance.setBusinessId(cmd.getBusinessId());
        }
        // 通知首个审批节点处理人
        notifyService.notifyOnStart(instance);
        return toVO(instance);
    }

    /**
     * 撤回流程（发起人操作）
     */
    public void revoke(Long instanceId) {
        List<Task> tasks = taskService.getByInsId(instanceId);
        if (tasks.isEmpty()) {
            throw new ServiceException("无可撤回的任务");
        }
        // 取第一个待审批任务进行撤回
        Task task = tasks.get(0);
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .message("发起人撤回");
        taskService.revoke(task.getId(), params);
        Instance instance = insService.getById(instanceId);
        if (instance != null) {
            notifyService.notifyOnRevoke(instance);
        }
    }

    /**
     * 终止流程
     */
    public void terminate(Long instanceId) {
        List<Task> tasks = taskService.getByInsId(instanceId);
        if (tasks.isEmpty()) {
            throw new ServiceException("无可终止的任务");
        }
        Task task = tasks.get(0);
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .message("流程终止");
        taskService.termination(task.getId(), params);
    }

    /**
     * 分页查询我发起的实例
     */
    public PageResult<WorkflowInstanceVO> pageMyInstances(TaskPageQuery query) {
        FlowInstance condition = new FlowInstance();
        if (StringUtils.hasText(query.getFlowName())) {
            condition.setFlowName(query.getFlowName());
        }
        condition.setCreateBy(currentHandler());
        Page<Instance> page = new Page<>(query.getPageNum(), query.getPageSize());
        page.setOrderBy("create_time");
        page.setIsAsc("desc");
        insService.page(condition, page);
        List<WorkflowInstanceVO> list = page.getList().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 实例详情
     */
    public WorkflowInstanceVO detail(Long instanceId) {
        Instance instance = insService.getById(instanceId);
        if (instance == null) {
            throw new ServiceException("流程实例不存在");
        }
        return toVO(instance);
    }

    private String currentHandler() {
        Long userId = LoginContext.getUserId();
        return userId != null ? String.valueOf(userId) : "anonymous";
    }

    private WorkflowInstanceVO toVO(Instance ins) {
        WorkflowInstanceVO vo = new WorkflowInstanceVO();
        vo.setId(ins.getId());
        vo.setDefinitionId(ins.getDefinitionId());
        vo.setFlowName(ins.getFlowName());
        vo.setBusinessId(ins.getBusinessId());
        vo.setNodeCode(ins.getNodeCode());
        vo.setNodeName(ins.getNodeName());
        vo.setFlowStatus(ins.getFlowStatus());
        vo.setActivityStatus(ins.getActivityStatus());
        vo.setCreateTime(toLocalDateTime(ins.getCreateTime()));
        vo.setUpdateTime(toLocalDateTime(ins.getUpdateTime()));
        return vo;
    }

    private java.time.LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }
}

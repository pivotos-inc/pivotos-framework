package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.workflow.domain.dto.TaskActionCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowHisTaskVO;
import com.pivotos.workflow.domain.vo.WorkflowTaskVO;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.HisTask;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.service.HisTaskService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.utils.page.Page;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 审批任务管理服务：待办 / 已办 / 审批通过 / 驳回 / 转办 / 委派。
 */
@Service
@RequiredArgsConstructor
public class FlowTaskService {

    private final TaskService taskService;
    private final HisTaskService hisTaskService;
    private final InsService insService;
    private final WorkflowNotifyService notifyService;

    /**
     * 我的待办分页（WarmFlow 按 PermissionHandler.permissions() 自动过滤）
     */
    public PageResult<WorkflowTaskVO> pagePending(TaskPageQuery query) {
        FlowTask condition = new FlowTask();
        if (StringUtils.hasText(query.getFlowName())) {
            condition.setFlowName(query.getFlowName());
        }
        Page<Task> page = new Page<>(query.getPageNum(), query.getPageSize());
        page.setOrderBy("create_time");
        page.setIsAsc("desc");
        taskService.page(condition, page);
        List<WorkflowTaskVO> list = page.getList().stream().map(this::toTaskVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 我的已办分页
     */
    public PageResult<WorkflowHisTaskVO> pageCompleted(TaskPageQuery query) {
        FlowHisTask condition = new FlowHisTask();
        condition.setApprover(currentHandler());
        Page<HisTask> page = new Page<>(query.getPageNum(), query.getPageSize());
        page.setOrderBy("create_time");
        page.setIsAsc("desc");
        hisTaskService.page(condition, page);
        List<WorkflowHisTaskVO> list = page.getList().stream().map(this::toHisVO).toList();
        return new PageResult<>(list, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 审批通过
     */
    public void pass(TaskActionCmd cmd) {
        Instance result = taskService.pass(cmd.getTaskId(), cmd.getMessage(), cmd.getVariable());
        if (result != null) {
            notifyService.notifyOnPass(result, cmd.getMessage());
        }
    }

    /**
     * 驳回
     */
    public void reject(TaskActionCmd cmd) {
        Instance result = taskService.reject(cmd.getTaskId(), cmd.getMessage(), cmd.getVariable());
        if (result != null) {
            notifyService.notifyOnReject(result, cmd.getMessage());
        }
    }

    /**
     * 转办（将任务转交给目标用户）
     */
    public void transfer(TaskActionCmd cmd) {
        if (!StringUtils.hasText(cmd.getTargetUserId())) {
            throw new ServiceException("转办目标用户不能为空");
        }
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .nextHandler(cmd.getTargetUserId())
                .message(cmd.getMessage());
        taskService.transfer(cmd.getTaskId(), params);
        // 查询任务信息用于通知
        Task task = taskService.getById(cmd.getTaskId());
        if (task != null) {
            notifyService.notifyOnTransfer(task, cmd.getTargetUserId());
        }
    }

    /**
     * 委派
     */
    public void depute(TaskActionCmd cmd) {
        if (!StringUtils.hasText(cmd.getTargetUserId())) {
            throw new ServiceException("委派目标用户不能为空");
        }
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .nextHandler(cmd.getTargetUserId())
                .message(cmd.getMessage());
        taskService.depute(cmd.getTaskId(), params);
    }

    /**
     * 查询实例审批历史
     */
    public List<WorkflowHisTaskVO> taskHistory(Long instanceId) {
        List<HisTask> list = hisTaskService.getByInsId(instanceId);
        return list.stream().map(this::toHisVO).toList();
    }

    /**
     * 待办数量（供 Facade 使用）
     */
    public long countPending() {
        FlowTask condition = new FlowTask();
        return taskService.selectCount(condition);
    }

    private String currentHandler() {
        Long userId = LoginContext.getUserId();
        return userId != null ? String.valueOf(userId) : "anonymous";
    }

    private WorkflowTaskVO toTaskVO(Task task) {
        WorkflowTaskVO vo = new WorkflowTaskVO();
        vo.setId(task.getId());
        vo.setDefinitionId(task.getDefinitionId());
        vo.setInstanceId(task.getInstanceId());
        vo.setFlowName(task.getFlowName());
        vo.setBusinessId(task.getBusinessId());
        vo.setNodeCode(task.getNodeCode());
        vo.setNodeName(task.getNodeName());
        vo.setNodeType(task.getNodeType());
        vo.setFlowStatus(task.getFlowStatus());
        vo.setCreateTime(toLocalDateTime(task.getCreateTime()));
        vo.setUpdateTime(toLocalDateTime(task.getUpdateTime()));
        return vo;
    }

    private WorkflowHisTaskVO toHisVO(HisTask his) {
        WorkflowHisTaskVO vo = new WorkflowHisTaskVO();
        vo.setId(his.getId());
        vo.setInstanceId(his.getInstanceId());
        vo.setTaskId(his.getTaskId());
        vo.setNodeCode(his.getNodeCode());
        vo.setNodeName(his.getNodeName());
        vo.setTargetNodeCode(his.getTargetNodeCode());
        vo.setTargetNodeName(his.getTargetNodeName());
        vo.setApprover(his.getApprover());
        vo.setSkipType(his.getSkipType());
        vo.setFlowStatus(his.getFlowStatus());
        vo.setMessage(his.getMessage());
        vo.setCreateTime(toLocalDateTime(his.getCreateTime()));
        return vo;
    }

    private java.time.LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return null;
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }
}

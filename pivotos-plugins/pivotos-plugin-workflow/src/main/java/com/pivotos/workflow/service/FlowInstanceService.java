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
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
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
    private final FlowCcService ccService;
    private final RedissonClient redissonClient;

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
        Instance instance;
        try {
            instance = insService.start(cmd.getFlowCode(), params);
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            // L2 清偿：引擎 FlowException 未翻译会落入全局兜底报「系统内部错误」（S93 遗留）
            throw new ServiceException(translateStartFailure(e));
        }
        if (instance == null) {
            throw new ServiceException("流程实例创建失败，请检查流程定义是否已发布");
        }
        // 设置业务 ID（WarmFlow start 不直接接受 businessId，需更新）
        String bizId = StringUtils.hasText(cmd.getBusinessId()) ? cmd.getBusinessId() : cmd.getBusinessName();
        if (StringUtils.hasText(bizId)) {
            FlowInstance update = new FlowInstance();
            update.setId(instance.getId());
            update.setBusinessId(bizId);
            insService.updateById(update);
            instance.setBusinessId(bizId);
            // 同步更新 Task 表，确保待办列表直接取到 businessId
            List<Task> tasks = taskService.getByInsId(instance.getId());
            if (tasks != null) {
                for (Task t : tasks) {
                    FlowTask taskUpdate = new FlowTask();
                    taskUpdate.setId(t.getId());
                    taskUpdate.setBusinessId(bizId);
                    taskService.updateById(taskUpdate);
                }
            }
        }
        // 通知首个审批节点处理人（引擎返回的 instance 可能未回填 flowName，从定义补齐）
        if (!StringUtils.hasText(instance.getFlowName())) {
            Definition def = defService.getById(instance.getDefinitionId());
            if (def != null) {
                instance.setFlowName(def.getFlowName());
            }
        }
        notifyService.notifyOnStart(instance);
        // 发起抄送（S78 F1）：落库 flow_cc + 站内信通知收件人（落库返回的实际收件人口径）
        List<Long> ccReceivers = ccService.recordCc(instance, cmd.getCcUserIds());
        notifyService.notifyOnCc(instance, ccReceivers);
        return toVO(instance);
    }

    /**
     * 撤回流程（发起人操作）
     * <p>
     * WarmFlow TaskService.revoke(Long instanceId, FlowParams) 第一个参数是实例 ID。
     */
    public void revoke(Long instanceId) {
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .message("发起人撤回");
        taskService.revoke(instanceId, params);
        Instance instance = insService.getById(instanceId);
        if (instance != null) {
            notifyService.notifyOnRevoke(instance);
        }
    }

    /**
     * 终止流程
     * <p>
     * WarmFlow TaskService.terminationByInsId(Long instanceId, FlowParams) 按实例 ID 终止。
     */
    public void terminate(Long instanceId) {
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .message("流程终止");
        taskService.terminationByInsId(instanceId, params);
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
        Page<Instance> result = insService.page(condition, page);
        Page<Instance> finalPage = result != null ? result : page;
        List<WorkflowInstanceVO> list = finalPage.getList() != null
                ? finalPage.getList().stream().map(this::toVO).toList() : List.of();
        return new PageResult<>(list, finalPage.getTotal(), query.getPageNum(), query.getPageSize());
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

    /**
     * 发起失败文案翻译（L2）：网关条件全不匹配等引擎异常转为业务友好提示，
     * 其余 FlowException 带原文，避免全局兜底的「系统内部错误」。
     */
    private String translateStartFailure(org.dromara.warm.flow.core.exception.FlowException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("条件") || msg.toLowerCase().contains("condition")) {
            return "发起失败：流程变量未匹配到任何网关分支条件，请检查表单填写或为排他网关配置默认分支";
        }
        return "流程发起失败：" + msg;
    }

    private String currentHandler() {
        Long userId = LoginContext.getUserId();
        return userId != null ? String.valueOf(userId) : "anonymous";
    }

    /**
     * 催办（S77 F2）：仅发起人、仅进行中实例，Redis 限频 10 分钟。
     */
    public void urge(Long instanceId) {
        Instance instance = insService.getById(instanceId);
        if (instance == null) {
            throw new ServiceException("流程实例不存在");
        }
        Long userId = LoginContext.getUserId();
        if (userId == null || !String.valueOf(userId).equals(instance.getCreateBy())) {
            throw new ServiceException("仅流程发起人可催办");
        }
        String status = instance.getFlowStatus();
        if (!"0".equals(status) && !"1".equals(status)) {
            // 退回态（FlowStatus.REJECT=9）流程并未办结，是回到提交人待重提：
            // 统一说「已办结」会误导发起人以为这事已经结束（S116 搭车项②）
            throw new ServiceException("9".equals(status)
                    ? "流程已退回，请处理后重新提交，无需催办"
                    : "流程已办结，无需催办");
        }
        RBucket<String> bucket = redissonClient.getBucket("workflow:urge:" + instanceId);
        if (!bucket.setIfAbsent(String.valueOf(userId), Duration.ofMinutes(10))) {
            throw new ServiceException("催办过于频繁，请 10 分钟后再试");
        }
        notifyService.notifyOnUrge(instance);
    }

    private WorkflowInstanceVO toVO(Instance ins) {
        WorkflowInstanceVO vo = new WorkflowInstanceVO();
        vo.setId(ins.getId());
        vo.setDefinitionId(ins.getDefinitionId());
        vo.setFlowName(ins.getFlowName());
        // flow_instance 表不含 flow_name 列，需从 definition 补全
        if (!StringUtils.hasText(vo.getFlowName())) {
            Definition def = defService.getById(ins.getDefinitionId());
            if (def != null) {
                vo.setFlowName(def.getFlowName());
            }
        }
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

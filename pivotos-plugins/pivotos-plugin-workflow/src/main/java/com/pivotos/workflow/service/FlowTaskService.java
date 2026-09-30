package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.facade.IUserFacade;
import com.pivotos.workflow.api.dto.ApprovalTaskContextDTO;
import com.pivotos.workflow.domain.dto.AddSignatureCmd;
import com.pivotos.workflow.domain.dto.ReductionSignatureCmd;
import com.pivotos.workflow.domain.dto.TaskActionCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.UserOptionVO;
import com.pivotos.workflow.domain.vo.WorkflowHisTaskVO;
import com.pivotos.workflow.domain.vo.WorkflowTaskVO;
import com.pivotos.workflow.mapper.WorkflowPendingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.HisTask;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.entity.User;
import org.dromara.warm.flow.core.enums.UserType;
import org.dromara.warm.flow.core.service.DefService;
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
import java.util.Map;

/**
 * 审批任务管理服务：待办 / 已办 / 审批通过 / 驳回 / 转办 / 委派。
 */
@Service
@RequiredArgsConstructor
public class FlowTaskService {

    /** 退回态状态码（warm-flow FlowStatus.REJECT：9=已退回），W1 重新提交的前置校验口径 */
    private static final String REJECT_STATUS = "9";

    private final TaskService taskService;
    private final HisTaskService hisTaskService;
    private final InsService insService;
    private final DefService defService;
    private final WorkflowNotifyService notifyService;
    private final ObjectProvider<IUserFacade> userFacadeProvider;
    private final WorkflowPendingMapper pendingMapper;

    /**
     * 我的待办分页。
     * <p>
     * 归属过滤（S93 修正）：warm-flow 1.8.7 的 taskService.page 是纯实体条件分页，
     * 不做 PermissionHandler.permissions() 过滤（早期注释有误），任何登录用户会看到全部待办。
     * 改走 WorkflowPendingMapper 按 flow_user 归属（审批/转办/委派）子查询分页。
     */
    public PageResult<WorkflowTaskVO> pagePending(TaskPageQuery query) {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            return new PageResult<>(List.of(), 0L, query.getPageNum(), query.getPageSize());
        }
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<FlowTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getPageNum(), query.getPageSize());
        com.baomidou.mybatisplus.core.metadata.IPage<FlowTask> result =
                pendingMapper.selectPendingPage(page, String.valueOf(userId), query.getFlowName());
        List<WorkflowTaskVO> list = result.getRecords().stream().map(this::toTaskVO).toList();
        return new PageResult<>(list, result.getTotal(), query.getPageNum(), query.getPageSize());
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
        Page<HisTask> result = hisTaskService.page(condition, page);
        Page<HisTask> finalPage = result != null ? result : page;
        List<WorkflowHisTaskVO> list = finalPage.getList() != null
                ? finalPage.getList().stream().map(this::toHisVO).toList() : List.of();
        return new PageResult<>(list, finalPage.getTotal(), query.getPageNum(), query.getPageSize());
    }

    /**
     * 审批通过
     */
    public void pass(TaskActionCmd cmd) {
        Instance result;
        try {
            result = taskService.pass(cmd.getTaskId(), cmd.getMessage(), cmd.getVariable());
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("审批通过失败：" + e.getMessage());
        }
        if (result != null) {
            notifyService.notifyOnPass(result, cmd.getMessage());
        }
    }

    /**
     * 驳回
     */
    public void reject(TaskActionCmd cmd) {
        Instance result;
        try {
            result = taskService.reject(cmd.getTaskId(), cmd.getMessage(), cmd.getVariable());
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("当前节点不支持驳回操作，请检查流程定义中是否配置了驳回路径");
        }
        if (result != null) {
            notifyService.notifyOnReject(result, cmd.getMessage());
        }
    }

    /**
     * 重新提交（W1 / S113）：发起人把被退回的任务重新提交，走引擎 pass 重走流程。
     * <p>
     * 引擎事实（S113 开工简报真库实证）：驳回后 {@code flow_task} 记录保留且
     * {@code flow_status=9}（node_code 回到发起节点）、{@code flow_user} 归属仍为发起人；
     * warm-flow 1.8.7 无 restart 原语（{@code RE_START} 枚举为死代码），
     * 但对退回态任务调用 {@code taskService.pass} **原生接受**——实例 {@code 9 → 1}、
     * 推进下一节点、实例 ID 不变、审批历史连续（驳回留痕 + 重新提交留痕同链）。
     * 故本动作不动引擎、不重置状态，只做前置校验后复用 pass。
     * <p>
     * 守卫：① 任务存在；② 任务必须处于退回态（9）—— 避免「通过」语义被滥用；
     * ③ 归属校验（同 S82 L3 口径）—— 仅该任务的办理人（即发起人）可重新提交。
     */
    public void resubmit(TaskActionCmd cmd) {
        if (cmd.getTaskId() == null) {
            throw new ServiceException("任务 ID 不能为空");
        }
        Task task = taskService.getById(cmd.getTaskId());
        if (task == null) {
            throw new ServiceException("任务不存在或已办结");
        }
        if (!REJECT_STATUS.equals(String.valueOf(task.getFlowStatus()))) {
            throw new ServiceException("仅已退回的任务可重新提交");
        }
        requireApprover(cmd.getTaskId());
        Instance result;
        try {
            result = taskService.pass(cmd.getTaskId(), cmd.getMessage(), cmd.getVariable());
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("重新提交失败：" + e.getMessage());
        }
        if (result != null) {
            notifyService.notifyOnPass(result, cmd.getMessage());
        }
    }

    /**
     * 转办（将任务转交给目标用户）
     * <p>
     * 引擎契约（S93 修正）：transfer 断言 addHandlers 而非 nextHandler，
     * 且引擎不校办理人归属，由 requireApprover 在服务层兜底（同 S82 L3 口径）。
     */
    public void transfer(TaskActionCmd cmd) {
        requireNotRejected(cmd.getTaskId());
        if (!StringUtils.hasText(cmd.getTargetUserId())) {
            throw new ServiceException("转办目标用户不能为空");
        }
        requireApprover(cmd.getTaskId());
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .addHandlers(List.of(cmd.getTargetUserId()))
                .message(cmd.getMessage());
        try {
            taskService.transfer(cmd.getTaskId(), params);
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("转办失败：" + e.getMessage());
        }
        // 查询任务信息用于通知
        Task task = taskService.getById(cmd.getTaskId());
        if (task != null) {
            notifyService.notifyOnTransfer(task, cmd.getTargetUserId());
        }
    }

    /**
     * 委派（受托人代审，通过后任务回到委派人确认）
     * <p>
     * 引擎契约（S93 修正）：depute 断言 addHandlers 而非 nextHandler；
     * 归属校验同转办。委派与转办的语义差异由引擎 cooperateType 留痕区分。
     */
    public void depute(TaskActionCmd cmd) {
        requireNotRejected(cmd.getTaskId());
        if (!StringUtils.hasText(cmd.getTargetUserId())) {
            throw new ServiceException("委派目标用户不能为空");
        }
        requireApprover(cmd.getTaskId());
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .addHandlers(List.of(cmd.getTargetUserId()))
                .message(cmd.getMessage());
        try {
            taskService.depute(cmd.getTaskId(), params);
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("委派失败：" + e.getMessage());
        }
        Task task = taskService.getById(cmd.getTaskId());
        if (task != null) {
            notifyService.notifyOnDepute(task, cmd.getTargetUserId());
        }
    }

    /**
     * 加签（S78 F2）：为待办任务追加审批人。
     * <p>
     * 走 warm-flow 原生 addSignature：被加签人写入 flow_user（type=APPROVAL）+
     * his_task 留痕（cooperateType=ADD_SIGNATURE）；或签语义，任一审批人通过即推进。
     * 重复加签拦截由引擎内置；办理人归属校验引擎不做，由 requireApprover 在服务层兜底（S82 L3）。
     */
    public void addSignature(AddSignatureCmd cmd) {
        if (cmd.getTaskId() == null) {
            throw new ServiceException("任务 ID 不能为空");
        }
        if (cmd.getUserIds() == null || cmd.getUserIds().isEmpty()) {
            throw new ServiceException("加签目标用户不能为空");
        }
        List<String> userIds = cmd.getUserIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            throw new ServiceException("加签目标用户不能为空");
        }
        requireNotRejected(cmd.getTaskId());
        requireApprover(cmd.getTaskId());
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .addHandlers(userIds)
                .message(cmd.getMessage());
        try {
            taskService.addSignature(cmd.getTaskId(), params);
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("加签失败：" + e.getMessage());
        }
        // 通知被加签人（任务信息用于补齐流程名/节点名）
        Task task = taskService.getById(cmd.getTaskId());
        if (task != null) {
            notifyService.notifyOnAddSignature(task, userIds);
        }
    }

    /**
     * 减签（S82）：从待办任务移除审批人。
     * <p>
     * 走 warm-flow 原生 reductionSignature：his_task 留痕（cooperateType=REDUCTION_SIGNATURE）。
     * 人数安全底线由引擎内置：办理人不足或只有一人时拒绝减签（节点不会减空）；
     * 办理人归属校验引擎不做，由 requireApprover 在服务层兜底（S82 L3）。
     */
    public void reductionSignature(ReductionSignatureCmd cmd) {
        if (cmd.getTaskId() == null) {
            throw new ServiceException("任务 ID 不能为空");
        }
        if (cmd.getUserIds() == null || cmd.getUserIds().isEmpty()) {
            throw new ServiceException("减签目标用户不能为空");
        }
        List<String> userIds = cmd.getUserIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            throw new ServiceException("减签目标用户不能为空");
        }
        requireNotRejected(cmd.getTaskId());
        requireApprover(cmd.getTaskId());
        FlowParams params = FlowParams.build()
                .handler(currentHandler())
                .reductionHandlers(userIds)
                .message(cmd.getMessage());
        try {
            taskService.reductionSignature(cmd.getTaskId(), params);
        } catch (org.dromara.warm.flow.core.exception.FlowException e) {
            throw new ServiceException("减签失败：" + e.getMessage());
        }
    }

    /**
     * 待办任务当前审批人（S82）：减签选人候选。
     * <p>
     * 与引擎减签护栏同口径取 APPROVAL + TRANSFER 两类 flow_user；
     * IUserFacade 未装配时昵称降级为用户 ID。
     */
    public List<UserOptionVO> taskApprovers(Long taskId) {
        if (taskId == null) {
            throw new ServiceException("任务 ID 不能为空");
        }
        List<User> users = FlowEngine.userService().listByAssociatedAndTypes(taskId,
                UserType.APPROVAL.getKey(), UserType.TRANSFER.getKey(), UserType.DEPUTE.getKey());
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<String> processedBys = users.stream().map(User::getProcessedBy).distinct().toList();
        // 批量补齐昵称（Facade 未装配/解析失败降级为裸 ID）
        java.util.Map<String, String> nicknameMap = java.util.Map.of();
        IUserFacade facade = userFacadeProvider.getIfAvailable();
        if (facade != null) {
            try {
                List<Long> ids = processedBys.stream().map(this::parseLongQuiet).filter(java.util.Objects::nonNull).toList();
                nicknameMap = facade.listByIds(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(u -> String.valueOf(u.getId()),
                                u -> u.getNickname() != null ? u.getNickname() : u.getUsername(), (a, b) -> a));
            } catch (Exception e) {
                // 降级：不阻塞减签选人
            }
        }
        java.util.Map<String, String> finalMap = nicknameMap;
        return processedBys.stream().map(id -> {
            UserOptionVO vo = new UserOptionVO();
            Long uid = parseLongQuiet(id);
            vo.setId(uid != null ? uid : 0L);
            vo.setUsername(id);
            vo.setNickname(finalMap.get(id));
            return vo;
        }).toList();
    }

    private Long parseLongQuiet(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 办理人归属校验（S82 L3）：仅当前任务的待办审批人可加签/减签。
     * <p>
     * warm-flow 加签/减签引擎层只校参数与人数，不校调用者归属，
     * 故在服务层兜底：非本任务审批人拒绝，防止任意登录用户操作他人任务。
     */
    /**
     * 当前待办的审批人数量（A4E / S117）：取值口径与 {@link #requireApprover} 完全一致
     * （APPROVAL + TRANSFER + DEPUTE 三类 flow_user 去重后的办理人数），
     * 避免「归属判定」与「是否单审批人判定」用两套口径而对不上。
     */
    private int countApprovers(Long taskId) {
        List<User> users = FlowEngine.userService().listByAssociatedAndTypes(taskId,
                UserType.APPROVAL.getKey(), UserType.TRANSFER.getKey(), UserType.DEPUTE.getKey());
        if (users == null) {
            return 0;
        }
        return (int) users.stream()
                .map(User::getProcessedBy)
                .filter(StringUtils::hasText)
                .distinct()
                .count();
    }

    private void requireApprover(Long taskId) {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException("未登录或登录已过期");
        }
        List<User> users = FlowEngine.userService().listByAssociatedAndTypes(taskId,
                UserType.APPROVAL.getKey(), UserType.TRANSFER.getKey(), UserType.DEPUTE.getKey());
        boolean isApprover = users != null && users.stream()
                .anyMatch(u -> String.valueOf(userId).equals(u.getProcessedBy()));
        if (!isApprover) {
            throw new ServiceException("仅当前任务的审批人可执行此操作");
        }
    }

    /**
     * 退回态守卫（W1 / S114 波及面回归）：已退回（{@code flow_status=9}）的任务不接受审批协同类动作，
     * 唯一合法动作是「重新提交」。
     * <p>
     * 背景：S113 在双端把退回态的加签/减签/转办/委派入口隐藏了，后端却未设闸。S114 波及面专项实证出
     * 四条真实通路（真库 API 级，非仅前端可达）：
     * <ul>
     *   <li>加签 / 转办 / 委派：可让第三人取得退回任务的办理权，进而代替发起人重新提交（实测受让人
     *       {@code pass} 后实例 {@code 9 → 1} 直接推进下一节点，历史只留「转办」无「重新提交」语义）；</li>
     *   <li>减签：引擎人数护栏只校「办理人不足或仅剩一人」，加签凑够两人后减掉发起人即可放行，
     *       实测发起人待办归零、退回单被他人接管——W1 修掉的「退回即死单」会以另一种形态复现。</li>
     * </ul>
     * 退回态语义上只有「发起人重新提交」一条合法动作，故在此统一设闸，与双端隐藏入口的口径对齐。
     * 与 {@link #resubmit} 的守卫互为镜像：那边「必须 9 才放行」，这边「是 9 就不放行」。
     */
    private void requireNotRejected(Long taskId) {
        if (taskId == null) {
            throw new ServiceException("任务 ID 不能为空");
        }
        Task task = taskService.getById(taskId);
        if (task != null && REJECT_STATUS.equals(String.valueOf(task.getFlowStatus()))) {
            throw new ServiceException("已退回的任务仅可重新提交，不支持该操作");
        }
    }

    /**
     * 查询实例审批历史
     */
    public List<WorkflowHisTaskVO> taskHistory(Long instanceId) {
        List<HisTask> list = hisTaskService.getByInsId(instanceId);
        return list.stream().map(this::toHisVO).toList();
    }

    /**
     * 审批上下文聚合（S101 A3）：实例信息 + 流程变量 + 审批历史，供跨插件 Facade 消费。
     * <p>归属闸：仅当前任务的待办审批人可取（口径同 requireApprover）；任务不存在返回 null。
     */
    public ApprovalTaskContextDTO approvalTaskContext(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            return null;
        }
        requireApprover(taskId);
        ApprovalTaskContextDTO dto = new ApprovalTaskContextDTO();
        dto.setTaskId(taskId);
        dto.setInstanceId(task.getInstanceId());
        dto.setNodeName(task.getNodeName());
        dto.setApproverCount(countApprovers(taskId));
        Instance ins = insService.getById(task.getInstanceId());
        if (ins != null) {
            String flowName = ins.getFlowName();
            // flow_instance 表不含 flow_name 列，需从 definition 补全（口径同 toTaskVO）
            if (!StringUtils.hasText(flowName)) {
                Definition def = defService.getById(ins.getDefinitionId());
                flowName = def != null ? def.getFlowName() : null;
            }
            dto.setFlowName(flowName);
            dto.setBusinessId(ins.getBusinessId());
            dto.setFlowStatus(ins.getFlowStatus());
            dto.setCreateBy(ins.getCreateBy());
            dto.setInstanceCreateTime(toLocalDateTime(ins.getCreateTime()));
            Map<String, Object> variables = ins.getVariableMap();
            dto.setVariables(variables != null ? variables : Map.of());
        } else {
            dto.setVariables(Map.of());
        }
        List<HisTask> hisList = hisTaskService.getByInsId(task.getInstanceId());
        dto.setHistory(hisList == null ? List.of() : hisList.stream().map(his -> {
            ApprovalTaskContextDTO.HistoryItem item = new ApprovalTaskContextDTO.HistoryItem();
            item.setNodeName(his.getNodeName());
            item.setApprover(his.getApprover());
            item.setSkipType(his.getSkipType());
            item.setMessage(his.getMessage());
            item.setCreateTime(toLocalDateTime(his.getCreateTime()));
            return item;
        }).toList());
        return dto;
    }

    /**
     * 待办数量（供 Facade 使用）：与 pagePending 同口径按归属过滤（S93）。
     */
    public long countPending() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            return 0L;
        }
        return pendingMapper.countPending(String.valueOf(userId), null);
    }

    /**
     * 加签选人用户选项（S81）：活跃用户 id/username/nickname。
     * <p>
     * IUserFacade 未装配时降级为空列表（与 S78 通知降级口径一致）。
     */
    public List<UserOptionVO> userOptions(String keyword) {
        IUserFacade facade = userFacadeProvider.getIfAvailable();
        if (facade == null) {
            return List.of();
        }
        return facade.listActiveOptions(50, keyword).stream().map(u -> {
            UserOptionVO vo = new UserOptionVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setNickname(u.getNickname());
            return vo;
        }).toList();
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
        // flow_task 表不含 flow_name/business_id，需从实例和定义表补充
        if (!StringUtils.hasText(vo.getFlowName()) || !StringUtils.hasText(vo.getBusinessId())) {
            Instance ins = insService.getById(task.getInstanceId());
            if (ins != null) {
                if (!StringUtils.hasText(vo.getBusinessId())) {
                    vo.setBusinessId(ins.getBusinessId());
                }
                if (!StringUtils.hasText(vo.getFlowName())) {
                    // flow_instance 也不含 flow_name 列，从 definition 取
                    Definition def = defService.getById(task.getDefinitionId());
                    if (def != null) {
                        vo.setFlowName(def.getFlowName());
                    }
                }
            }
        }
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
        vo.setCooperateType(his.getCooperateType());
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

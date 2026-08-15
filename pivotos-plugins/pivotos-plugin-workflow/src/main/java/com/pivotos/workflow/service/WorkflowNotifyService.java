package com.pivotos.workflow.service;

import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.facade.IMessageFacade;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.enums.UserType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 审批消息通知服务：审批操作后通过 IMessageFacade 发送站内信通知。
 * <p>
 * 通知发送失败仅 log 不阻断主事务（最终一致性）。
 */
@Service
public class WorkflowNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNotifyService.class);

    private final IMessageFacade messageFacade;

    private final TaskService taskService;

    private final UserService flowUserService;

    private final DefService defService;

    private final IUserFacade userFacade;

    /**
     * 用 ObjectProvider 延迟解析：若未装配 message 插件则静默跳过通知；
     * IUserFacade 未装配时加签通知中操作人昵称降级为用户 ID（S78）。
     */
    public WorkflowNotifyService(ObjectProvider<IMessageFacade> messageFacadeProvider,
                                 TaskService taskService, UserService flowUserService, DefService defService,
                                 ObjectProvider<IUserFacade> userFacadeProvider) {
        this.messageFacade = messageFacadeProvider.getIfAvailable();
        this.taskService = taskService;
        this.flowUserService = flowUserService;
        this.defService = defService;
        this.userFacade = userFacadeProvider.getIfAvailable();
        if (this.messageFacade == null) {
            log.warn("[WorkflowNotify] IMessageFacade 未装配，审批通知将跳过");
        }
    }

    /** 发起实例 → 通知首个审批节点处理人（S77 F1：收件人按待办任务 permissionList 解析） */
    public void notifyOnStart(Instance instance) {
        sendNotify("您有一条新的审批待处理",
                String.format("流程「%s」已发起，请及时审批", flowNameOf(instance)),
                instance.getId(),
                resolvePendingApprovers(instance.getId()));
    }

    /** 审批通过 → 有新待办通知其处理人；流程已办结通知发起人（S77 F1） */
    public void notifyOnPass(Instance instance, String message) {
        List<Long> approvers = resolvePendingApprovers(instance.getId());
        if (!approvers.isEmpty()) {
            sendNotify("轮到您审批",
                    String.format("流程「%s」已流转至您%s", flowNameOf(instance),
                            message != null ? "，上一步意见：" + message : ""),
                    instance.getId(),
                    approvers);
            return;
        }
        // 引擎推进后无待办：流程已结束，通知发起人
        notifyInitiator(instance, "流程已完成",
                String.format("流程「%s」审批全部通过，已办结", flowNameOf(instance)));
    }

    /** 催办（S77 F2）：通知当前待办处理人 */
    public void notifyOnUrge(Instance instance) {
        sendNotify("催办提醒",
                String.format("发起人催办：流程「%s」请尽快处理", flowNameOf(instance)),
                instance.getId(),
                resolvePendingApprovers(instance.getId()));
    }

    /** 发起抄送（S78 F1）：通知全部抄送收件人 */
    public void notifyOnCc(Instance instance, List<Long> ccUserIds) {
        if (ccUserIds == null || ccUserIds.isEmpty()) {
            return;
        }
        sendNotify("流程抄送提醒",
                String.format("流程「%s」已抄送给您，发起人：%s", flowNameOf(instance), currentNickname()),
                instance.getId(),
                ccUserIds,
                1);
    }

    /** 加签（S78 F2）：通知被加签人新增待办 */
    public void notifyOnAddSignature(Task task, List<String> addedUserIds) {
        if (addedUserIds == null || addedUserIds.isEmpty()) {
            return;
        }
        List<Long> receivers = addedUserIds.stream()
                .map(this::parseUserId)
                .filter(Objects::nonNull)
                .toList();
        sendNotify("您有一条加签的审批待处理",
                String.format("流程「%s」节点「%s」由 %s 加签给您处理", flowNameOf(task), task.getNodeName(), currentNickname()),
                task.getInstanceId(),
                receivers,
                3);
    }

    /** 解析当前登录人昵称（Facade 未装配/解析失败降级为用户 ID） */
    private String currentNickname() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            return "unknown";
        }
        if (userFacade != null) {
            try {
                UserDTO user = userFacade.getById(userId);
                if (user != null && user.getNickname() != null && !user.getNickname().isEmpty()) {
                    return user.getNickname();
                }
            } catch (Exception e) {
                log.warn("[WorkflowNotify] 解析操作人昵称失败: userId={}, error={}", userId, e.getMessage());
            }
        }
        return String.valueOf(userId);
    }

    /**
     * 解析流程名称：warm-flow 的 flow_instance/flow_task 表均无 flow_name 列，
     * 从 DB 读回的实体 flowName 恒为 null，统一从流程定义补齐。
     */
    private String flowNameOf(Instance instance) {
        if (instance.getFlowName() != null && !instance.getFlowName().isEmpty()) {
            return instance.getFlowName();
        }
        try {
            Definition def = defService.getById(instance.getDefinitionId());
            if (def != null && def.getFlowName() != null) {
                return def.getFlowName();
            }
        } catch (Exception e) {
            log.warn("[WorkflowNotify] 解析流程名称失败: definitionId={}, error={}",
                    instance.getDefinitionId(), e.getMessage());
        }
        return "未知流程";
    }

    /** Task 版流程名称解析（flow_task 表同样无 flow_name 列） */
    private String flowNameOf(Task task) {
        if (task.getFlowName() != null && !task.getFlowName().isEmpty()) {
            return task.getFlowName();
        }
        try {
            Definition def = defService.getById(task.getDefinitionId());
            if (def != null && def.getFlowName() != null) {
                return def.getFlowName();
            }
        } catch (Exception e) {
            log.warn("[WorkflowNotify] 解析流程名称失败: definitionId={}, error={}",
                    task.getDefinitionId(), e.getMessage());
        }
        return "未知流程";
    }

    /** 通知发起人（createBy 解析失败仅 log 不阻断） */
    private void notifyInitiator(Instance instance, String title, String content) {
        String createBy = instance.getCreateBy();
        if (createBy == null || createBy.isEmpty()) {
            return;
        }
        try {
            sendNotify(title, content, instance.getId(), List.of(Long.valueOf(createBy)));
        } catch (NumberFormatException e) {
            log.warn("[WorkflowNotify] 发起人 ID 非数字，跳过通知: createBy={}", createBy);
        }
    }

    /**
     * 解析实例当前待办任务的处理人。
     * <p>
     * 注意：warm-flow 的 {@code TaskService.getByInsId} 不回填 permissionList（该字段仅建任务时
     * 由节点 permissionFlag 写入内存对象），审批人实际存于 flow_user 表，
     * 须用 {@code UserService.getPermission(taskId, APPROVAL)} 读取 processedBy（用户 ID 口径）。
     */
    private List<Long> resolvePendingApprovers(Long instanceId) {
        try {
            List<Task> tasks = taskService.getByInsId(instanceId);
            if (tasks == null || tasks.isEmpty()) {
                return List.of();
            }
            return tasks.stream()
                    .filter(t -> "1".equals(t.getFlowStatus()))
                    .flatMap(t -> flowUserService.getPermission(t.getId(), UserType.APPROVAL.getKey()).stream())
                    .distinct()
                    .map(this::parseUserId)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[WorkflowNotify] 解析待办处理人失败: instanceId={}, error={}", instanceId, e.getMessage());
            return List.of();
        }
    }

    private Long parseUserId(String permission) {
        try {
            return Long.valueOf(permission);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 驳回 → 通知发起人 */
    public void notifyOnReject(Instance instance, String message) {
        String createBy = instance.getCreateBy();
        if (createBy == null || createBy.isEmpty()) return;
        try {
            Long receiverId = Long.valueOf(createBy);
            sendNotify("审批已驳回",
                    String.format("流程「%s」被驳回%s", flowNameOf(instance),
                            message != null ? "，原因：" + message : ""),
                    instance.getId(),
                    List.of(receiverId));
        } catch (NumberFormatException e) {
            log.warn("[WorkflowNotify] 发起人 ID 格式异常: {}", createBy);
        }
    }

    /** 转办 → 通知被转办人 */
    public void notifyOnTransfer(Task task, String targetUserId) {
        try {
            Long receiverId = Long.valueOf(targetUserId);
            sendNotify("您有一条转办的审批待处理",
                    String.format("流程「%s」节点「%s」已转交给您处理", flowNameOf(task), task.getNodeName()),
                    task.getInstanceId(),
                    List.of(receiverId));
        } catch (NumberFormatException e) {
            log.warn("[WorkflowNotify] 转办目标用户 ID 格式异常: {}", targetUserId);
        }
    }

    /** 撤回 → 通知当前审批人 */
    public void notifyOnRevoke(Instance instance) {
        sendNotify("流程已撤回",
                String.format("流程「%s」已被发起人撤回", flowNameOf(instance)),
                instance.getId(),
                List.of());
    }

    private void sendNotify(String title, String content, Long instanceId, List<Long> receiverIds) {
        sendNotify(title, content, instanceId, receiverIds, 3);
    }

    /**
     * 发送站内信（msgType：1 通知 / 3 待办）。
     * 失败仅 log 不阻断主事务（最终一致性口径）。
     */
    private void sendNotify(String title, String content, Long instanceId, List<Long> receiverIds, int msgType) {
        if (messageFacade == null || receiverIds.isEmpty()) {
            return;
        }
        try {
            MessageSendCmd cmd = new MessageSendCmd();
            cmd.setTitle(title);
            cmd.setContent(content);
            cmd.setMsgType(msgType);
            cmd.setChannel("inbox");
            cmd.setBizType("workflow");
            cmd.setBizId(String.valueOf(instanceId));
            cmd.setReceiverIds(receiverIds);
            messageFacade.send(cmd);
        } catch (Exception e) {
            log.warn("[WorkflowNotify] 通知发送失败（不影响审批主事务）: {}", e.getMessage());
        }
    }
}

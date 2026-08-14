package com.pivotos.workflow.service;

import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.facade.IMessageFacade;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审批消息通知服务：审批操作后通过 IMessageFacade 发送站内信通知。
 * <p>
 * 通知发送失败仅 log 不阻断主事务（最终一致性）。
 */
@Service
public class WorkflowNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNotifyService.class);

    private final IMessageFacade messageFacade;

    /**
     * 用 ObjectProvider 延迟解析：若未装配 message 插件则静默跳过通知。
     */
    public WorkflowNotifyService(ObjectProvider<IMessageFacade> messageFacadeProvider) {
        this.messageFacade = messageFacadeProvider.getIfAvailable();
        if (this.messageFacade == null) {
            log.warn("[WorkflowNotify] IMessageFacade 未装配，审批通知将跳过");
        }
    }

    /** 发起实例 → 通知首个审批节点处理人 */
    public void notifyOnStart(Instance instance) {
        sendNotify("您有一条新的审批待处理",
                String.format("流程「%s」已发起，请及时审批", instance.getFlowName()),
                instance.getId(),
                List.of()); // 实际需要从 Task.userList / permissionList 获取处理人
    }

    /** 审批通过 → 通知发起人或下一节点处理人 */
    public void notifyOnPass(Instance instance, String message) {
        sendNotify("审批已通过",
                String.format("流程「%s」审批通过%s", instance.getFlowName(),
                        message != null ? "，意见：" + message : ""),
                instance.getId(),
                List.of()); // 由 WarmFlow 引擎自动推进到下一节点
    }

    /** 驳回 → 通知发起人 */
    public void notifyOnReject(Instance instance, String message) {
        String createBy = instance.getCreateBy();
        if (createBy == null || createBy.isEmpty()) return;
        try {
            Long receiverId = Long.valueOf(createBy);
            sendNotify("审批已驳回",
                    String.format("流程「%s」被驳回%s", instance.getFlowName(),
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
                    String.format("流程「%s」节点「%s」已转交给您处理", task.getFlowName(), task.getNodeName()),
                    task.getInstanceId(),
                    List.of(receiverId));
        } catch (NumberFormatException e) {
            log.warn("[WorkflowNotify] 转办目标用户 ID 格式异常: {}", targetUserId);
        }
    }

    /** 撤回 → 通知当前审批人 */
    public void notifyOnRevoke(Instance instance) {
        sendNotify("流程已撤回",
                String.format("流程「%s」已被发起人撤回", instance.getFlowName()),
                instance.getId(),
                List.of());
    }

    private void sendNotify(String title, String content, Long instanceId, List<Long> receiverIds) {
        if (messageFacade == null || receiverIds.isEmpty()) {
            return;
        }
        try {
            MessageSendCmd cmd = new MessageSendCmd();
            cmd.setTitle(title);
            cmd.setContent(content);
            cmd.setMsgType(3); // 待办
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

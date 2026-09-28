package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.facade.IUserFacade;
import com.pivotos.workflow.domain.dto.AddSignatureCmd;
import com.pivotos.workflow.domain.dto.ReductionSignatureCmd;
import com.pivotos.workflow.domain.dto.TaskActionCmd;
import com.pivotos.workflow.mapper.WorkflowPendingMapper;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.HisTaskService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退回态守卫单测（W1 波及面回归 / S114）。
 * <p>
 * 锁的是 S113 只在前端隐藏入口、后端未设闸的缺口：已退回（{@code flow_status=9}）的任务，
 * 加签 / 减签 / 转办 / 委派四个协同动作必须拒绝，且绝不能触达引擎——
 * 否则第三人可取得退回任务的办理权代替发起人重新提交（加签/转办/委派），
 * 或把发起人从办理人中移除使其丧失重提入口（减签）。
 * <p>
 * 与 {@link FlowTaskServiceResubmitTest} 互为镜像：那边锁「非 9 不允重提」，这边锁「是 9 不允协同」。
 */
class FlowTaskServiceRejectedGuardTest {

    private TaskService taskService;
    private FlowTaskService service;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IUserFacade> userFacadeProvider = mock(ObjectProvider.class);
        service = new FlowTaskService(
                taskService,
                mock(HisTaskService.class),
                mock(InsService.class),
                mock(DefService.class),
                mock(WorkflowNotifyService.class),
                userFacadeProvider,
                mock(WorkflowPendingMapper.class));
    }

    @Test
    @DisplayName("退回态：加签拒绝，且不触达引擎")
    void addSignatureRejectedForRejectedTask() {
        stubTaskStatus("9");
        AddSignatureCmd cmd = new AddSignatureCmd();
        cmd.setTaskId(1L);
        cmd.setUserIds(List.of("2"));
        assertThatThrownBy(() -> service.addSignature(cmd))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已退回的任务仅可重新提交");
        verify(taskService, never()).addSignature(any(), any());
    }

    @Test
    @DisplayName("退回态：减签拒绝，且不触达引擎")
    void reductionSignatureRejectedForRejectedTask() {
        stubTaskStatus("9");
        ReductionSignatureCmd cmd = new ReductionSignatureCmd();
        cmd.setTaskId(1L);
        cmd.setUserIds(List.of("2"));
        assertThatThrownBy(() -> service.reductionSignature(cmd))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已退回的任务仅可重新提交");
        verify(taskService, never()).reductionSignature(any(), any());
    }

    @Test
    @DisplayName("退回态：转办拒绝，且不触达引擎")
    void transferRejectedForRejectedTask() {
        stubTaskStatus("9");
        assertThatThrownBy(() -> service.transfer(actionCmd("2")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已退回的任务仅可重新提交");
        verify(taskService, never()).transfer(any(), any());
    }

    @Test
    @DisplayName("退回态：委派拒绝，且不触达引擎")
    void deputeRejectedForRejectedTask() {
        stubTaskStatus("9");
        assertThatThrownBy(() -> service.depute(actionCmd("2")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已退回的任务仅可重新提交");
        verify(taskService, never()).depute(any(), any());
    }

    @Test
    @DisplayName("非退回态（审批中 1）：不受退回态守卫影响，流程继续走到归属校验")
    void notRejectedTaskPassesGuard() {
        stubTaskStatus("1");
        // 归属校验在没有登录上下文时抛「未登录」——只要不是退回态文案，就说明守卫未误伤普通待办
        assertThatThrownBy(() -> service.transfer(actionCmd("2")))
                .isInstanceOf(ServiceException.class)
                .hasMessageNotContaining("已退回的任务仅可重新提交");
        assertThatCode(() -> service.addSignature(addSignatureCmd())).isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("任务不存在时守卫不阻断（交由后续归属校验处理）")
    void absentTaskNotBlockedByGuard() {
        // mock 必须先构造完再进 stub（S113 K6：实参里现造 mock 会 UnfinishedStubbing）
        Task nullTask = null;
        when(taskService.getById(3L)).thenReturn(nullTask);
        assertThatThrownBy(() -> service.transfer(actionCmdWithTaskId(3L, "2")))
                .isInstanceOf(ServiceException.class)
                .hasMessageNotContaining("已退回的任务仅可重新提交");
        verify(taskService, never()).transfer(any(), any());
    }

    private void stubTaskStatus(String status) {
        Task task = mock(Task.class);
        when(task.getFlowStatus()).thenReturn(status);
        when(taskService.getById(1L)).thenReturn(task);
    }

    private static TaskActionCmd actionCmd(String targetUserId) {
        return actionCmdWithTaskId(1L, targetUserId);
    }

    private static TaskActionCmd actionCmdWithTaskId(Long taskId, String targetUserId) {
        TaskActionCmd cmd = new TaskActionCmd();
        cmd.setTaskId(taskId);
        cmd.setTargetUserId(targetUserId);
        cmd.setMessage("test");
        return cmd;
    }

    private static AddSignatureCmd addSignatureCmd() {
        AddSignatureCmd cmd = new AddSignatureCmd();
        cmd.setTaskId(1L);
        cmd.setUserIds(List.of("2"));
        cmd.setMessage("test");
        return cmd;
    }
}

package com.pivotos.workflow.service;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.facade.IUserFacade;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1（S113）重新提交动作的前置守卫单测。
 * <p>
 * 覆盖「不依赖引擎静态上下文」的三道守卫：任务 ID 缺失 / 任务不存在 / 非退回态。
 * 归属校验（{@code requireApprover}）与「真正推进」依赖 {@code FlowEngine} 静态上下文与登录上下文，
 * 由 E2E 真库全链路覆盖（s113_w1_resubmit_test.py：A2/A3/A6 等 10 项断言），此处不重复造轮子。
 * <p>
 * 关键锁：非退回态拒绝——否则「重新提交」会退化成第二个「通过」入口，绕过审批语义。
 */
class FlowTaskServiceResubmitTest {

    private TaskService taskService;
    private FlowTaskService service;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        WorkflowNotifyService notifyService = mock(WorkflowNotifyService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IUserFacade> userFacadeProvider = mock(ObjectProvider.class);
        service = new FlowTaskService(
                taskService,
                mock(HisTaskService.class),
                mock(InsService.class),
                mock(DefService.class),
                notifyService,
                userFacadeProvider,
                mock(WorkflowPendingMapper.class));
    }

    @Test
    @DisplayName("任务 ID 缺失：拒绝")
    void rejectWhenTaskIdMissing() {
        TaskActionCmd cmd = new TaskActionCmd();
        assertThatThrownBy(() -> service.resubmit(cmd))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("任务 ID 不能为空");
    }

    @Test
    @DisplayName("任务不存在：拒绝")
    void rejectWhenTaskAbsent() {
        when(taskService.getById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.resubmit(cmd(1L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("任务不存在或已办结");
    }

    @Test
    @DisplayName("非退回态（审批中 1）：拒绝，且绝不触发 pass（防语义绕过）")
    void rejectWhenNotRejectedAndNeverCallPass() {
        // 注意：mock 必须先构造再 stub——在 when(...) 实参里现造 mock 会触发 Mockito 嵌套 stubbing（UnfinishedStubbing）
        Task task = taskWithStatus("1");
        when(taskService.getById(1L)).thenReturn(task);
        assertThatThrownBy(() -> service.resubmit(cmd(1L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("仅已退回的任务可重新提交");
        verify(taskService, never()).pass(any(), any(), any());
    }

    @Test
    @DisplayName("非退回态（已完成 8 等其余取值）：一律拒绝")
    void rejectForOtherStatuses() {
        for (String status : new String[]{"0", "2", "4", "6", "8"}) {
            Task task = taskWithStatus(status);
            when(taskService.getById(2L)).thenReturn(task);
            assertThatThrownBy(() -> service.resubmit(cmd(2L)))
                    .as("status=%s 不应允许重新提交", status)
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("仅已退回的任务可重新提交");
        }
    }

    private static TaskActionCmd cmd(Long taskId) {
        TaskActionCmd cmd = new TaskActionCmd();
        cmd.setTaskId(taskId);
        return cmd;
    }

    private static Task taskWithStatus(String status) {
        Task task = mock(Task.class);
        when(task.getFlowStatus()).thenReturn(status);
        return task;
    }
}

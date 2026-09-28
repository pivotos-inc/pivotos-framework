package com.pivotos.workflow.controller;

import com.pivotos.common.core.page.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.workflow.domain.dto.AddSignatureCmd;
import com.pivotos.workflow.domain.dto.ReductionSignatureCmd;
import com.pivotos.workflow.domain.dto.TaskActionCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.UserOptionVO;
import com.pivotos.workflow.domain.vo.WorkflowHisTaskVO;
import com.pivotos.workflow.domain.vo.WorkflowTaskVO;
import com.pivotos.workflow.service.FlowTaskService;
import com.pivotos.workflow.support.WorkflowAuthSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批任务管理：待办 / 已办 / 审批通过 / 驳回 / 转办 / 委派 / 加签 / 减签 / 审批历史。
 * <p>端点三体系通用（S79）：仅校「已登录」，待办人归属由 warm-flow 引擎层校验。
 */
@Tag(name = "审批任务", description = "待办/已办/审批/驳回/转办/委派/加签")
@RestController
@RequestMapping("/workflow/task")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final FlowTaskService flowTaskService;

    @Operation(summary = "待办任务分页")
    @GetMapping("/pending/page")
    public R<PageResult<WorkflowTaskVO>> pagePending(TaskPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.pagePending(query));
    }

    @Operation(summary = "已办任务分页")
    @GetMapping("/completed/page")
    public R<PageResult<WorkflowHisTaskVO>> pageCompleted(TaskPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.pageCompleted(query));
    }

    @Operation(summary = "通过任务")
    @PutMapping("/pass")
    public R<Void> pass(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.pass(cmd);
        return R.ok();
    }

    @Operation(summary = "驳回任务")
    @PutMapping("/reject")
    public R<Void> reject(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.reject(cmd);
        return R.ok();
    }

    /**
     * 重新提交（W1 / S113）：发起人将「已退回」任务重新提交，实例 ID 与审批历史保持连续。
     * 权限口径同 pass/reject（仅校登录 + 服务层归属校验），零新增权限码。
     */
    @Operation(summary = "重新提交（W1：退回任务重新提交，实例与历史连续）")
    @PutMapping("/resubmit")
    public R<Void> resubmit(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.resubmit(cmd);
        return R.ok();
    }

    @Operation(summary = "转办任务")
    @PutMapping("/transfer")
    public R<Void> transfer(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.transfer(cmd);
        return R.ok();
    }

    @Operation(summary = "委派任务")
    @PutMapping("/depute")
    public R<Void> depute(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.depute(cmd);
        return R.ok();
    }

    /** 加签（S78 F2）：为待办任务追加审批人（warm-flow 原生或签语义） */
    @Operation(summary = "加签（S78 F2）：为待办任务追加审批人（warm-flow 原生或签语义）")
    @PutMapping("/add-signature")
    public R<Void> addSignature(@RequestBody AddSignatureCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.addSignature(cmd);
        return R.ok();
    }

    /** 减签（S82）：从待办任务移除审批人（引擎护栏：办理人不足两人不可减签） */
    @Operation(summary = "减签（S82）：从待办任务移除审批人（引擎护栏：办理人不足两人不可减签）")
    @PutMapping("/reduction-signature")
    public R<Void> reductionSignature(@RequestBody ReductionSignatureCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.reductionSignature(cmd);
        return R.ok();
    }

    /** 待办任务当前审批人（S82）：减签选人候选 */
    @Operation(summary = "待办任务当前审批人（S82）：减签选人候选")
    @GetMapping("/{taskId}/approvers")
    public R<List<UserOptionVO>> taskApprovers(@PathVariable Long taskId) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.taskApprovers(taskId));
    }

    @Operation(summary = "审批历史")
    @GetMapping("/history/{instanceId}")
    public R<List<WorkflowHisTaskVO>> taskHistory(@PathVariable Long instanceId) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.taskHistory(instanceId));
    }

    /** 加签选人用户选项（S81）：活跃用户 id/username/nickname，支持关键字检索 */
    @Operation(summary = "加签选人用户选项（S81）：活跃用户 id/username/nickname，支持关键字检索")
    @GetMapping("/user-options")
    public R<List<UserOptionVO>> userOptions(@RequestParam(required = false) String keyword) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.userOptions(keyword));
    }
}

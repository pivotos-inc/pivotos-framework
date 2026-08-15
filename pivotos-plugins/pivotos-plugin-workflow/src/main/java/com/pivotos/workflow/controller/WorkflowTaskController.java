package com.pivotos.workflow.controller;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.workflow.domain.dto.AddSignatureCmd;
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
 * 审批任务管理：待办 / 已办 / 审批通过 / 驳回 / 转办 / 委派 / 加签 / 审批历史。
 * <p>端点三体系通用（S79）：仅校「已登录」，待办人归属由 warm-flow 引擎层校验。
 */
@RestController
@RequestMapping("/workflow/task")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final FlowTaskService flowTaskService;

    @GetMapping("/pending/page")
    public R<PageResult<WorkflowTaskVO>> pagePending(TaskPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.pagePending(query));
    }

    @GetMapping("/completed/page")
    public R<PageResult<WorkflowHisTaskVO>> pageCompleted(TaskPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.pageCompleted(query));
    }

    @PutMapping("/pass")
    public R<Void> pass(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.pass(cmd);
        return R.ok();
    }

    @PutMapping("/reject")
    public R<Void> reject(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.reject(cmd);
        return R.ok();
    }

    @PutMapping("/transfer")
    public R<Void> transfer(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.transfer(cmd);
        return R.ok();
    }

    @PutMapping("/depute")
    public R<Void> depute(@RequestBody TaskActionCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.depute(cmd);
        return R.ok();
    }

    /** 加签（S78 F2）：为待办任务追加审批人（warm-flow 原生或签语义） */
    @PutMapping("/add-signature")
    public R<Void> addSignature(@RequestBody AddSignatureCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        flowTaskService.addSignature(cmd);
        return R.ok();
    }

    @GetMapping("/history/{instanceId}")
    public R<List<WorkflowHisTaskVO>> taskHistory(@PathVariable Long instanceId) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.taskHistory(instanceId));
    }

    /** 加签选人用户选项（S81）：活跃用户 id/username/nickname，支持关键字检索 */
    @GetMapping("/user-options")
    public R<List<UserOptionVO>> userOptions(@RequestParam(required = false) String keyword) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowTaskService.userOptions(keyword));
    }
}

package com.pivotos.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.workflow.domain.dto.AddSignatureCmd;
import com.pivotos.workflow.domain.dto.TaskActionCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowHisTaskVO;
import com.pivotos.workflow.domain.vo.WorkflowTaskVO;
import com.pivotos.workflow.service.FlowTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批任务管理：待办 / 已办 / 审批通过 / 驳回 / 转办 / 委派 / 加签 / 审批历史。
 */
@RestController
@RequestMapping("/workflow/task")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final FlowTaskService flowTaskService;

    @GetMapping("/pending/page")
    @SaCheckPermission(value = "workflow:task:pending", type = StpSysUtil.TYPE)
    public R<PageResult<WorkflowTaskVO>> pagePending(TaskPageQuery query) {
        return R.ok(flowTaskService.pagePending(query));
    }

    @GetMapping("/completed/page")
    @SaCheckPermission(value = "workflow:task:completed", type = StpSysUtil.TYPE)
    public R<PageResult<WorkflowHisTaskVO>> pageCompleted(TaskPageQuery query) {
        return R.ok(flowTaskService.pageCompleted(query));
    }

    @PutMapping("/pass")
    @SaCheckPermission(value = "workflow:task:approve", type = StpSysUtil.TYPE)
    public R<Void> pass(@RequestBody TaskActionCmd cmd) {
        flowTaskService.pass(cmd);
        return R.ok();
    }

    @PutMapping("/reject")
    @SaCheckPermission(value = "workflow:task:approve", type = StpSysUtil.TYPE)
    public R<Void> reject(@RequestBody TaskActionCmd cmd) {
        flowTaskService.reject(cmd);
        return R.ok();
    }

    @PutMapping("/transfer")
    @SaCheckPermission(value = "workflow:task:transfer", type = StpSysUtil.TYPE)
    public R<Void> transfer(@RequestBody TaskActionCmd cmd) {
        flowTaskService.transfer(cmd);
        return R.ok();
    }

    @PutMapping("/depute")
    @SaCheckPermission(value = "workflow:task:depute", type = StpSysUtil.TYPE)
    public R<Void> depute(@RequestBody TaskActionCmd cmd) {
        flowTaskService.depute(cmd);
        return R.ok();
    }

    /** 加签（S78 F2）：为待办任务追加审批人（warm-flow 原生或签语义） */
    @PutMapping("/add-signature")
    @SaCheckPermission(value = "workflow:task:add-signature", type = StpSysUtil.TYPE)
    public R<Void> addSignature(@RequestBody AddSignatureCmd cmd) {
        flowTaskService.addSignature(cmd);
        return R.ok();
    }

    @GetMapping("/history/{instanceId}")
    @SaCheckPermission(value = "workflow:task:history", type = StpSysUtil.TYPE)
    public R<List<WorkflowHisTaskVO>> taskHistory(@PathVariable Long instanceId) {
        return R.ok(flowTaskService.taskHistory(instanceId));
    }
}

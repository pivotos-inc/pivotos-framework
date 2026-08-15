package com.pivotos.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.workflow.domain.dto.StartInstanceCmd;
import com.pivotos.workflow.domain.dto.TaskPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowInstanceVO;
import com.pivotos.workflow.service.FlowInstanceService;
import com.pivotos.workflow.support.WorkflowAuthSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程实例管理：发起 / 撤回 / 终止 / 详情 / 我发起的列表。
 * <p>
 * 端点三体系通用（S79）：start/revoke/urge/详情/列表仅校「已登录」，
 * 发起人归属由 service 层校验；终止为管理态操作，保留 sys 权限。
 */
@RestController
@RequestMapping("/workflow/instance")
@RequiredArgsConstructor
public class WorkflowInstanceController {

    private final FlowInstanceService flowInstanceService;

    @PostMapping("/start")
    public R<WorkflowInstanceVO> start(@RequestBody StartInstanceCmd cmd) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowInstanceService.start(cmd));
    }

    @PutMapping("/{instanceId}/revoke")
    public R<Void> revoke(@PathVariable Long instanceId) {
        WorkflowAuthSupport.requireUserId();
        flowInstanceService.revoke(instanceId);
        return R.ok();
    }

    @PutMapping("/{instanceId}/terminate")
    @SaCheckPermission(value = "workflow:instance:terminate", type = StpSysUtil.TYPE)
    public R<Void> terminate(@PathVariable Long instanceId) {
        flowInstanceService.terminate(instanceId);
        return R.ok();
    }

    /** 催办（S77 F2）：发起人催促当前审批人，10 分钟限频 */
    @PutMapping("/{instanceId}/urge")
    public R<Void> urge(@PathVariable Long instanceId) {
        WorkflowAuthSupport.requireUserId();
        flowInstanceService.urge(instanceId);
        return R.ok();
    }

    @GetMapping("/{instanceId}")
    public R<WorkflowInstanceVO> detail(@PathVariable Long instanceId) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowInstanceService.detail(instanceId));
    }

    @GetMapping("/page")
    public R<PageResult<WorkflowInstanceVO>> page(TaskPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowInstanceService.pageMyInstances(query));
    }
}

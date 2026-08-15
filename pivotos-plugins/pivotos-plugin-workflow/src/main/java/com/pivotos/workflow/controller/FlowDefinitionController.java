package com.pivotos.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.workflow.domain.dto.FlowDefinitionQuery;
import com.pivotos.workflow.domain.vo.FlowDefinitionVO;
import com.pivotos.workflow.service.FlowDefinitionService;
import com.pivotos.workflow.support.WorkflowAuthSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程定义管理。
 * <p>
 * 流程定义的新增/编辑由 WarmFlow 内置设计器 UI 处理（/warm-flow-ui/**），
 * 本 Controller 提供：分页列表 + 详情 + 发布 + 挂起/激活 + 删除。
 * <p>
 * 端点三体系通用（S79）：列表/详情仅校「已登录」（移动端发起流程需选流程），
 * 发布/挂起激活/删除为管理态操作，保留 sys 权限。
 */
@RestController
@RequestMapping("/workflow/definition")
@RequiredArgsConstructor
public class FlowDefinitionController {

    private final FlowDefinitionService flowDefinitionService;

    @GetMapping("/page")
    public R<PageResult<FlowDefinitionVO>> page(FlowDefinitionQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowDefinitionService.pageDefinitions(query));
    }

    @GetMapping("/{id}")
    public R<FlowDefinitionVO> get(@PathVariable Long id) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowDefinitionService.getDefinition(id));
    }

    @PutMapping("/{id}/publish")
    @SaCheckPermission(value = "workflow:definition:publish", type = StpSysUtil.TYPE)
    public R<Void> publish(@PathVariable Long id) {
        flowDefinitionService.publish(id);
        return R.ok();
    }

    @PutMapping("/{id}/toggle-activity")
    @SaCheckPermission(value = "workflow:definition:edit", type = StpSysUtil.TYPE)
    public R<Void> toggleActivity(@PathVariable Long id) {
        flowDefinitionService.toggleActivity(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "workflow:definition:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        flowDefinitionService.delete(id);
        return R.ok();
    }
}

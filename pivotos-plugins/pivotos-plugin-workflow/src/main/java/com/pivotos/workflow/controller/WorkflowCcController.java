package com.pivotos.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.workflow.domain.dto.CcPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowCcVO;
import com.pivotos.workflow.service.FlowCcService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程抄送管理（S78 F1）：抄送我的列表 + 已读标记。
 */
@RestController
@RequestMapping("/workflow/cc")
@RequiredArgsConstructor
public class WorkflowCcController {

    private final FlowCcService flowCcService;

    /** 抄送我的分页（仅本人数据） */
    @GetMapping("/page")
    @SaCheckPermission(value = "workflow:cc:list", type = StpSysUtil.TYPE)
    public R<PageResult<WorkflowCcVO>> page(CcPageQuery query) {
        return R.ok(flowCcService.pageMine(query));
    }

    /** 标记已读（仅本人记录） */
    @PutMapping("/{id}/read")
    @SaCheckPermission(value = "workflow:cc:list", type = StpSysUtil.TYPE)
    public R<Void> markRead(@PathVariable Long id) {
        flowCcService.markRead(id);
        return R.ok();
    }
}

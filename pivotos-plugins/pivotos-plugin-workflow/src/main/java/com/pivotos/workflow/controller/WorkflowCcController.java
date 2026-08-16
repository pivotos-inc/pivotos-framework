package com.pivotos.workflow.controller;

import com.pivotos.common.core.page.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.result.R;
import com.pivotos.workflow.domain.dto.CcPageQuery;
import com.pivotos.workflow.domain.vo.WorkflowCcVO;
import com.pivotos.workflow.service.FlowCcService;
import com.pivotos.workflow.support.WorkflowAuthSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程抄送管理（S78 F1）：抄送我的列表 + 已读标记。
 * <p>
 * 端点三体系通用（S79）：仅校「已登录」，仅本人数据由 service 层兜底。
 */
@Tag(name = "流程抄送", description = "抄送列表 + 已读标记")
@RestController
@RequestMapping("/workflow/cc")
@RequiredArgsConstructor
public class WorkflowCcController {

    private final FlowCcService flowCcService;

    /** 抄送我的分页（仅本人数据） */
    @Operation(summary = "抄送我的分页（仅本人数据）")
    @GetMapping("/page")
    public R<PageResult<WorkflowCcVO>> page(CcPageQuery query) {
        WorkflowAuthSupport.requireUserId();
        return R.ok(flowCcService.pageMine(query));
    }

    /** 标记已读（仅本人记录） */
    @Operation(summary = "标记已读（仅本人记录）")
    @PutMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id) {
        WorkflowAuthSupport.requireUserId();
        flowCcService.markRead(id);
        return R.ok();
    }
}

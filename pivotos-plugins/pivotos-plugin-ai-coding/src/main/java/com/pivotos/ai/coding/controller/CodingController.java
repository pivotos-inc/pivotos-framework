package com.pivotos.ai.coding.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.pivotos.ai.coding.api.dto.CodingRequest;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import org.springframework.web.bind.annotation.*;

/**
 * AI Coding REST controller.
 * <p>
 * POST /ai-coding/parse - Parse natural language and generate code
 * GET  /ai-coding/session/{id} - View session detail
 * POST /ai-coding/session/{id}/apply - Apply generated code to project
 * <p>
 * PC 管理端功能（菜单挂在「AI 助手」下），类级 @SaCheckLogin 要求 sys 体系登录，
 * 不设按钮级权限（同 V1.2.13 菜单设计）；apply 会写工程文件，绝不能裸奔。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@RestController
@RequestMapping("/ai-coding")
@SaCheckLogin(type = StpSysUtil.TYPE)
public class CodingController {

    private final CodingService codingService;

    public CodingController(CodingService codingService) {
        this.codingService = codingService;
    }

    /**
     * Parse natural language description and generate code preview.
     */
    @PostMapping("/parse")
    public R<CodingSessionVO> parse(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGenerate(request.getDescription()));
    }

    /**
     * Page query coding sessions (list view, without generated files).
     */
    @GetMapping("/session/page")
    public R<com.pivotos.common.core.page.PageResult<CodingSessionVO>> pageSessions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.ok(codingService.pageSessions(pageNum, pageSize));
    }

    /**
     * Get session detail with generated file list.
     */
    @GetMapping("/session/{id}")
    public R<CodingSessionVO> session(@PathVariable Long id) {
        return R.ok(codingService.getSession(id));
    }

    /**
     * Apply generated code files to project directories.
     */
    @PostMapping("/session/{id}/apply")
    public R<Void> apply(@PathVariable Long id) {
        codingService.applyToProject(id);
        return R.ok();
    }
}

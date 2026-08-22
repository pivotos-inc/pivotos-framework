package com.pivotos.ai.coding.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.coding.api.dto.CodingRequest;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.core.context.LoginContext;
import org.springframework.web.bind.annotation.*;

/**
 * AI Coding REST controller.
 * <p>
 * POST /ai-coding/parse - Parse natural language and generate code
 * GET  /ai-coding/session/page - Page query own sessions
 * GET  /ai-coding/session/{id} - View own session detail
 * POST /ai-coding/session/{id}/apply - Apply generated code to project
 * <p>
 * PC 管理端功能（菜单挂在「AI 助手」下）：类级 @SaCheckLogin 要求 sys 体系登录，
 * 各端点再按 V1.2.16 按钮菜单做 @SaCheckPermission（super_admin 走通配）；
 * 会话归属 create_by 行级隔离在 Service 层统一校验（S41），apply 会写工程文件，绝不能裸奔。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Tag(name = "AI 代码生成", description = "自然语言生成代码")
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
    @Operation(summary = "解析需求生成代码")
    @PostMapping("/parse")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> parse(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGenerate(request.getDescription()));
    }

    /**
     * Parse natural language and generate a new plugin skeleton (S42 / 2.2-F12).
     */
    @Operation(summary = "解析插件")
    @PostMapping("/plugin/parse")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> parsePlugin(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGeneratePlugin(request.getDescription()));
    }

    /**
     * Parse natural language and generate master-detail (主子表) code (S52 / 2.4-F5).
     */
    @Operation(summary = "解析子表")
    @PostMapping("/sub/parse")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> parseSub(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGenerateSub(request.getDescription()));
    }

    /**
     * Parse natural language and generate tree table code (S54 / tree intent).
     */
    @Operation(summary = "解析表结构树")
    @PostMapping("/tree/parse")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> parseTree(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGenerateTree(request.getDescription()));
    }

    /**
     * Page query coding sessions (list view, without generated files).
     */
    @Operation(summary = "生成会话分页")
    @GetMapping("/session/page")
    @SaCheckPermission(value = "ai:coding:list", type = StpSysUtil.TYPE)
    public R<PageResult<CodingSessionVO>> pageSessions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.ok(codingService.pageSessions(requireUserId(), pageNum, pageSize));
    }

    /**
     * Get session detail with generated file list.
     */
    @Operation(summary = "生成会话详情")
    @GetMapping("/session/{id}")
    @SaCheckPermission(value = "ai:coding:list", type = StpSysUtil.TYPE)
    public R<CodingSessionVO> session(@PathVariable Long id) {
        return R.ok(codingService.getSession(requireUserId(), id));
    }

    /**
     * Apply generated code files to project directories.
     */
    @Operation(summary = "应用生成代码")
    @PostMapping("/session/{id}/apply")
    @SaCheckPermission(value = "ai:coding:apply", type = StpSysUtil.TYPE)
    public R<Void> apply(@PathVariable Long id) {
        codingService.applyToProject(requireUserId(), id);
        return R.ok();
    }

    /** 取当前登录用户 ID（类级 @SaCheckLogin 已兜底，此处防御式校验 → 1002） */
    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}

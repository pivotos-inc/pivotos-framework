package com.pivotos.ai.coding.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.coding.api.dto.LocateRequest;
import com.pivotos.ai.coding.api.dto.LocateResultVO;
import com.pivotos.ai.coding.locate.CodeLocateService;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A4-1 代码定位端点（两段定位：粗筛 top-N → 并行精定位 → 确定性仲裁）。
 *
 * <p>零 Flyway 约束（S110 口径）：本 Sprint 不新增菜单/按钮，权限复用 V1.2.16 已落库的
 * {@code ai:coding:parse}；定位是修改型链路的第一步，与「解析需求生成代码」同属 AI Coding 面。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Tag(name = "AI 代码定位", description = "修改型任务的两段定位")
@RestController
@RequestMapping("/ai-coding/locate")
@SaCheckLogin(type = StpSysUtil.TYPE)
public class CodeLocateController {

    private final CodeLocateService locateService;

    public CodeLocateController(CodeLocateService locateService) {
        this.locateService = locateService;
    }

    /**
     * 两段定位：意图 → 候选 → 精定位 → 仲裁落点。
     */
    @Operation(summary = "代码定位")
    @PostMapping
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<LocateResultVO> locate(@RequestBody LocateRequest request) {
        return R.ok(locateService.locate(request));
    }

    /**
     * 强制重建指定仓库的代码索引（源码变更后刷新）。
     */
    @Operation(summary = "重建代码索引")
    @PostMapping("/index/rebuild")
    @SaCheckPermission(value = "ai:coding:parse", type = StpSysUtil.TYPE)
    public R<Integer> rebuild(@RequestParam(defaultValue = "fw") String repo) {
        return R.ok(locateService.rebuild(repo));
    }

    /**
     * 索引统计（仓库 → 文件数）。
     */
    @Operation(summary = "代码索引统计")
    @GetMapping("/index/stats")
    @SaCheckPermission(value = "ai:coding:list", type = StpSysUtil.TYPE)
    public R<Map<String, Integer>> stats() {
        return R.ok(locateService.indexStats());
    }
}

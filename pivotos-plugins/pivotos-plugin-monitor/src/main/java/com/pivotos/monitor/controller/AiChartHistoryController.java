package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.domain.dto.AiChartSaveCmd;
import com.pivotos.monitor.domain.vo.AiChartHistoryVO;
import com.pivotos.monitor.service.AiChartHistoryService;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 图表历史（S83 报表大屏四期）：保存收藏 / 我的历史分页 / 删除。
 * 数据归属在服务层强校验（仅保存人可见可删），权限复用 monitor:dashboard:view。
 */
@RestController
@RequestMapping("/monitor/ai-chart-history")
@RequiredArgsConstructor
public class AiChartHistoryController {

    private final AiChartHistoryService aiChartHistoryService;

    /** 保存当前生成的图表（收藏制，返回记录 ID） */
    @PostMapping
    @SaCheckPermission(value = "monitor:dashboard:view", type = StpSysUtil.TYPE)
    public R<Long> save(@RequestBody AiChartSaveCmd cmd) {
        return R.ok(aiChartHistoryService.save(cmd));
    }

    /** 我的历史分页（按保存时间倒序） */
    @GetMapping("/page")
    @SaCheckPermission(value = "monitor:dashboard:view", type = StpSysUtil.TYPE)
    public R<PageResult<AiChartHistoryVO>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.ok(aiChartHistoryService.pageMine(pageNum, pageSize));
    }

    /** 删除（仅归属人可删） */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "monitor:dashboard:view", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        aiChartHistoryService.delete(id);
        return R.ok();
    }
}

package com.pivotos.mind.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.mind.api.dto.KnowledgeQuery;
import com.pivotos.mind.api.dto.KnowledgeSaveBody;
import com.pivotos.mind.api.vo.KnowledgeVO;
import com.pivotos.mind.service.MindKnowledgeService;
import com.pivotos.starter.auth.account.StpMindUtil;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智域知识库接口
 */
@Tag(name = "智域知识库", description = "PivotOS·智域个人知识库")
@RestController
@RequestMapping("/mind/knowledge")
@RequiredArgsConstructor
@SaCheckLogin(type = StpMindUtil.TYPE)
public class MindKnowledgeController {

    private final MindKnowledgeService knowledgeService;

    @Operation(summary = "分页列表")
    @GetMapping("/list")
    public R<PageResult<KnowledgeVO>> list(KnowledgeQuery query) {
        return R.ok(knowledgeService.pageKnowledge(requireUserId(), query));
    }

    @Operation(summary = "最近知识")
    @GetMapping("/recent")
    public R<List<KnowledgeVO>> recent(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(knowledgeService.recentKnowledge(requireUserId(), limit));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public R<KnowledgeVO> detail(@PathVariable Long id) {
        return R.ok(knowledgeService.getKnowledge(requireUserId(), id));
    }

    @Operation(summary = "创建")
    @PostMapping
    public R<Long> create(@Validated @RequestBody KnowledgeSaveBody body) {
        return R.ok(knowledgeService.createKnowledge(requireUserId(), body));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody KnowledgeSaveBody body) {
        knowledgeService.updateKnowledge(requireUserId(), id, body);
        return R.ok();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        knowledgeService.deleteKnowledge(requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "统计")
    @GetMapping("/stats")
    public R<Long> stats() {
        return R.ok(knowledgeService.countByUser(requireUserId()));
    }

    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new IllegalStateException("登录态异常");
        }
        return userId;
    }
}

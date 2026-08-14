package com.pivotos.ai.kb.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.kb.domain.dto.KbEvalRunRequest;
import com.pivotos.ai.kb.domain.dto.KbEvalSaveRequest;
import com.pivotos.ai.kb.domain.vo.KbEvalCompareVO;
import com.pivotos.ai.kb.domain.vo.KbEvalQuestionVO;
import com.pivotos.ai.kb.service.KbEvalService;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
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
 * 知识库检索评测接口（S66）。
 *
 * <p>问题集 CRUD 复用 ai:kb:edit 权限点，列表/跑分复用 ai:kb:list，不新增权限点。
 * 跑分为单题同步执行（前端逐题调用，天然支持进度展示）。
 */
@RestController
@RequestMapping("/ai/kb/eval")
@RequiredArgsConstructor
public class KbEvalController {

    private final KbEvalService kbEvalService;

    /** 评测问题集列表 */
    @GetMapping("/question/list")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<List<KbEvalQuestionVO>> list(@RequestParam Long kbId) {
        return R.ok(kbEvalService.listByKb(kbId));
    }

    /** 新增评测问题 */
    @PostMapping("/question")
    @SaCheckPermission(value = "ai:kb:edit", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody KbEvalSaveRequest request) {
        return R.ok(kbEvalService.create(request));
    }

    /** 修改评测问题 */
    @PutMapping("/question")
    @SaCheckPermission(value = "ai:kb:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody KbEvalSaveRequest request) {
        kbEvalService.update(request);
        return R.ok();
    }

    /** 删除评测问题 */
    @DeleteMapping("/question/{id}")
    @SaCheckPermission(value = "ai:kb:edit", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        kbEvalService.delete(id);
        return R.ok();
    }

    /** 单题跑分：rerank 关（基线）/ 开 双配置对比 */
    @PostMapping("/run")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<KbEvalCompareVO> run(@Validated @RequestBody KbEvalRunRequest request) {
        return R.ok(kbEvalService.runOne(request.getQuestionId(),
                request.getTopK() == null ? 5 : request.getTopK()));
    }
}

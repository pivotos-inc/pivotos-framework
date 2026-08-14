package com.pivotos.ai.kb.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.kb.domain.dto.KbBaseSaveRequest;
import com.pivotos.ai.kb.domain.dto.KbBaseUpdateRequest;
import com.pivotos.ai.kb.domain.dto.KbSearchRequest;
import com.pivotos.ai.kb.domain.vo.KnowledgeBaseVO;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.ai.kb.service.KnowledgeBaseService;
import com.pivotos.common.core.page.PageQuery;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口。
 */
@RestController
@RequestMapping("/ai/kb/base")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbPipelineService pipelineService;

    /** 分页列表 */
    @GetMapping("/page")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<PageResult<KnowledgeBaseVO>> page(PageQuery query) {
        return R.ok(knowledgeBaseService.page(query));
    }

    /** 下拉选择 */
    @GetMapping("/list")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<List<KnowledgeBaseVO>> list() {
        return R.ok(knowledgeBaseService.listSimple());
    }

    /** 详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<KnowledgeBaseVO> get(@PathVariable Long id) {
        return R.ok(knowledgeBaseService.get(id));
    }

    /** 新增 */
    @PostMapping
    @SaCheckPermission(value = "ai:kb:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody KbBaseSaveRequest request) {
        return R.ok(knowledgeBaseService.create(request));
    }

    /** 修改 */
    @PutMapping
    @SaCheckPermission(value = "ai:kb:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody KbBaseUpdateRequest request) {
        knowledgeBaseService.update(request);
        return R.ok();
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "ai:kb:delete", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return R.ok();
    }

    /** 相似性检索（调试/管理用） */
    @PostMapping("/search")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<List<Map<String, Object>>> search(@Validated @RequestBody KbSearchRequest request) {
        List<Document> docs = knowledgeBaseService.search(request.getKbId(),
                request.getQuery(), request.getTopK() == null ? 5 : request.getTopK());
        // Document.text → content，对齐前端 KbSearchResult 类型
        List<Map<String, Object>> results = docs.stream()
                .map(doc -> Map.<String, Object>of(
                        "content", doc.getText(),
                        "score", doc.getScore() != null ? doc.getScore() : 0.0,
                        "metadata", doc.getMetadata()))
                .toList();
        return R.ok(results);
    }
}

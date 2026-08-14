package com.pivotos.ai.kb.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.ai.kb.domain.dto.KbDocPageQuery;
import com.pivotos.ai.kb.domain.dto.KbDocUploadRequest;
import com.pivotos.ai.kb.domain.vo.KbDocumentVO;
import com.pivotos.ai.kb.service.KbDocumentService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库文档管理接口。
 */
@RestController
@RequestMapping("/ai/kb/doc")
@RequiredArgsConstructor
public class KbDocumentController {

    private final KbDocumentService documentService;

    /** 分页列表 */
    @GetMapping("/page")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<PageResult<KbDocumentVO>> page(KbDocPageQuery query) {
        return R.ok(documentService.page(query));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "ai:kb:list", type = StpSysUtil.TYPE)
    public R<KbDocumentVO> get(@PathVariable Long id) {
        return R.ok(documentService.get(id));
    }

    /** 上传并触发向量化 */
    @PostMapping("/upload")
    @SaCheckPermission(value = "ai:kb:doc:add", type = StpSysUtil.TYPE)
    public R<Long> upload(@Validated @RequestBody KbDocUploadRequest request) {
        return R.ok(documentService.upload(request));
    }

    /** 删除文档并清向量 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "ai:kb:doc:delete", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return R.ok();
    }

    /** 重新向量化 */
    @PostMapping("/{id}/reindex")
    @SaCheckPermission(value = "ai:kb:doc:reindex", type = StpSysUtil.TYPE)
    public R<Void> reindex(@PathVariable Long id) {
        documentService.reindex(id);
        return R.ok();
    }
}

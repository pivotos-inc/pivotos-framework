package com.pivotos.message.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.message.domain.dto.TemplateQuery;
import com.pivotos.message.domain.dto.TemplateSaveRequest;
import com.pivotos.message.domain.vo.TemplateVO;
import com.pivotos.message.service.TemplateService;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.web.annotation.RepeatSubmit;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 消息模板管理 */
@Tag(name = "消息模板", description = "消息模板管理")
@RestController
@RequestMapping("/message/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @Operation(summary = "消息模板分页")
    @GetMapping("/page")
    @SaCheckPermission(value = "message:template:list", type = StpSysUtil.TYPE)
    public R<PageResult<TemplateVO>> page(TemplateQuery query) {
        return R.ok(templateService.pageTemplates(query));
    }

    @Operation(summary = "消息模板详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "message:template:query", type = StpSysUtil.TYPE)
    public R<TemplateVO> get(@PathVariable Long id) {
        return R.ok(templateService.getTemplate(id));
    }

    @Operation(summary = "新增消息模板")
    @PostMapping
    @RepeatSubmit
    @SaCheckPermission(value = "message:template:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody TemplateSaveRequest request) {
        return R.ok(templateService.createTemplate(request));
    }

    @Operation(summary = "修改消息模板")
    @PutMapping
    @SaCheckPermission(value = "message:template:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody TemplateSaveRequest request) {
        templateService.updateTemplate(request);
        return R.ok();
    }

    @Operation(summary = "删除消息模板")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "message:template:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }
}

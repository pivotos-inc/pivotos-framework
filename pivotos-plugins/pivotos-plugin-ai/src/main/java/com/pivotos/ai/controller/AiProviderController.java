package com.pivotos.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.ai.domain.dto.ApiKeySaveRequest;
import com.pivotos.ai.domain.dto.ProviderSaveRequest;
import com.pivotos.ai.domain.vo.ApiKeyVO;
import com.pivotos.ai.domain.vo.ProviderOptionVO;
import com.pivotos.ai.domain.vo.ProviderVO;
import com.pivotos.ai.service.AiProviderService;
import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 供应商与 API Key 管理接口。
 * 管理端点走 ai:provider:* 权限（Key 增删改归并到 edit/remove，避免权限点碎片化）；
 * options / models 是对话页下拉数据，登录即可用（与 /ai/chat 同款鉴权模式）。
 */
@Tag(name = "AI 供应商", description = "AI 供应商与 API Key 管理")
@RestController
@RequestMapping("/ai/provider")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderService aiProviderService;

    // ---------- 供应商管理（权限） ----------

    /** 供应商列表（含停用，附启用 Key 数） */
    @Operation(summary = "供应商列表（含停用，附启用 Key 数）")
    @GetMapping("/list")
    @SaCheckPermission(value = "ai:provider:list", type = StpSysUtil.TYPE)
    public R<List<ProviderVO>> list() {
        return R.ok(aiProviderService.listProviders());
    }

    /** 新增供应商 */
    @Operation(summary = "新增供应商")
    @PostMapping
    @SaCheckPermission(value = "ai:provider:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody ProviderSaveRequest request) {
        return R.ok(aiProviderService.createProvider(request));
    }

    /** 修改供应商 */
    @Operation(summary = "修改供应商")
    @PutMapping
    @SaCheckPermission(value = "ai:provider:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody ProviderSaveRequest request) {
        aiProviderService.updateProvider(request);
        return R.ok();
    }

    /** 删除供应商（级联删除其 Key） */
    @Operation(summary = "删除供应商（级联删除其 Key）")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "ai:provider:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        aiProviderService.deleteProvider(id);
        return R.ok();
    }

    // ---------- Key 管理（权限） ----------

    /** Key 列表（脱敏，只回尾 4 位） */
    @Operation(summary = "Key 列表（脱敏，只回尾 4 位）")
    @GetMapping("/{id}/keys")
    @SaCheckPermission(value = "ai:provider:list", type = StpSysUtil.TYPE)
    public R<List<ApiKeyVO>> listKeys(@PathVariable Long id) {
        return R.ok(aiProviderService.listKeys(id));
    }

    /** 新增 Key（明文仅此一次入站，AES 落库不可回看） */
    @Operation(summary = "新增 Key（明文仅此一次入站，AES 落库不可回看）")
    @PostMapping("/key")
    @SaCheckPermission(value = "ai:provider:edit", type = StpSysUtil.TYPE)
    public R<Long> createKey(@Validated @RequestBody ApiKeySaveRequest request) {
        return R.ok(aiProviderService.createKey(request));
    }

    /** 修改 Key（apiKey 留空 = 不变更本体） */
    @Operation(summary = "修改 Key（apiKey 留空 = 不变更本体）")
    @PutMapping("/key")
    @SaCheckPermission(value = "ai:provider:edit", type = StpSysUtil.TYPE)
    public R<Void> updateKey(@Validated @RequestBody ApiKeySaveRequest request) {
        aiProviderService.updateKey(request);
        return R.ok();
    }

    /** 删除 Key */
    @Operation(summary = "删除 Key")
    @DeleteMapping("/key/{id}")
    @SaCheckPermission(value = "ai:provider:remove", type = StpSysUtil.TYPE)
    public R<Void> deleteKey(@PathVariable Long id) {
        aiProviderService.deleteKey(id);
        return R.ok();
    }

    // ---------- 对话侧下拉（登录即可） ----------

    /** 启用供应商选项（对话页供应商下拉） */
    @Operation(summary = "启用供应商选项（对话页供应商下拉）")
    @GetMapping("/options")
    public R<List<ProviderOptionVO>> options() {
        requireLogin();
        return R.ok(aiProviderService.listOptions());
    }

    /** 供应商可用模型（对话页模型下拉，动态查供应商 /models，5 分钟缓存） */
    @Operation(summary = "供应商可用模型（对话页模型下拉，动态查供应商 /models，5 分钟缓存）")
    @GetMapping("/{id}/models")
    public R<List<String>> models(@PathVariable Long id) {
        requireLogin();
        return R.ok(aiProviderService.listModels(id));
    }

    /** 三体系统一登录校验（未登录 → 1002，与 Sa-Token 未登录同码） */
    private void requireLogin() {
        if (LoginContext.getUserId() == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}

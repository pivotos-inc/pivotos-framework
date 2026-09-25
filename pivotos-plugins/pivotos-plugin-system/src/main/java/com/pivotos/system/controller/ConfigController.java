package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.ConfigQuery;
import com.pivotos.system.domain.dto.ConfigSaveRequest;
import com.pivotos.system.domain.vo.ConfigVO;
import com.pivotos.system.service.ConfigService;
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

/** 参数配置管理 */
@Tag(name = "参数配置", description = "系统参数配置管理")
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @Operation(summary = "参数配置分页")
    @GetMapping("/page")
    @SaCheckPermission(value = "system:config:list", type = StpSysUtil.TYPE)
    public R<PageResult<ConfigVO>> page(ConfigQuery query) {
        return R.ok(configService.pageConfigs(query));
    }

    @Operation(summary = "参数配置详情")
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:config:query", type = StpSysUtil.TYPE)
    public R<ConfigVO> get(@PathVariable Long id) {
        return R.ok(configService.getConfig(id));
    }

    @Operation(summary = "新增参数配置")
    @PostMapping
    @SaCheckPermission(value = "system:config:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody ConfigSaveRequest request) {
        return R.ok(configService.createConfig(request));
    }

    @Operation(summary = "修改参数配置")
    @PutMapping
    @SaCheckPermission(value = "system:config:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody ConfigSaveRequest request) {
        configService.updateConfig(request);
        return R.ok();
    }

    /** 按键名读参数值（登录即可读，供前端读取功能开关；不存在时返回 null） */
    @Operation(summary = "按键名读参数值（登录即可读）")
    @GetMapping("/configKey/{configKey}")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<String> getByKey(@PathVariable String configKey) {
        return R.ok(configService.getConfigValue(configKey, null));
    }

    @Operation(summary = "删除参数配置")
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:config:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        configService.deleteConfig(id);
        return R.ok();
    }
}

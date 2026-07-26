package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping("/page")
    @SaCheckPermission(value = "system:config:list", type = StpSysUtil.TYPE)
    public R<PageResult<ConfigVO>> page(ConfigQuery query) {
        return R.ok(configService.pageConfigs(query));
    }

    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:config:query", type = StpSysUtil.TYPE)
    public R<ConfigVO> get(@PathVariable Long id) {
        return R.ok(configService.getConfig(id));
    }

    @PostMapping
    @SaCheckPermission(value = "system:config:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody ConfigSaveRequest request) {
        return R.ok(configService.createConfig(request));
    }

    @PutMapping
    @SaCheckPermission(value = "system:config:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody ConfigSaveRequest request) {
        configService.updateConfig(request);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:config:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        configService.deleteConfig(id);
        return R.ok();
    }
}

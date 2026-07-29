package com.pivotos.starter.tenant.it.fixture;

import com.pivotos.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测试夹具 Controller：租户表 + 平台表的最小读写端点。
 * 无鉴权（tenant Starter 不依赖 auth），租户经 X-Tenant-Id 请求头进入。
 */
@RestController
@RequiredArgsConstructor
public class DemoController {

    private final DemoTenantMapper tenantMapper;
    private final DemoPlatformMapper platformMapper;

    @PostMapping("/demo")
    public R<Long> create(@RequestBody Map<String, String> body) {
        DemoTenantDO entity = new DemoTenantDO();
        entity.setTitle(body.get("title"));
        tenantMapper.insert(entity);
        return R.ok(entity.getId());
    }

    @GetMapping("/demo/list")
    public R<List<DemoTenantDO>> list() {
        return R.ok(tenantMapper.selectList(null));
    }

    @PostMapping("/platform")
    public R<Long> createPlatform(@RequestBody Map<String, String> body) {
        DemoPlatformDO entity = new DemoPlatformDO();
        entity.setName(body.get("name"));
        platformMapper.insert(entity);
        return R.ok(entity.getId());
    }

    @GetMapping("/platform/list")
    public R<List<DemoPlatformDO>> listPlatform() {
        return R.ok(platformMapper.selectList(null));
    }
}

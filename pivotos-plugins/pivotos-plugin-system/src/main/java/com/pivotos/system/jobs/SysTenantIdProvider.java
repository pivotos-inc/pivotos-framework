package com.pivotos.system.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.starter.job.retention.TenantIdProvider;
import com.pivotos.system.domain.entity.SysTenant;
import com.pivotos.system.mapper.SysTenantMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 租户 ID 枚举实现（留存清理「逐租户执行」的数据来源）。
 * <p>
 * 由 plugin-system 提供：租户表 {@code sys_tenant} 的实体只在本模块，
 * 而 ArchUnit A3 禁止 starter 依赖 plugin，故 starter 只定接口、这里给实现，
 * 各插件的清理任务经 SPI 拿到租户列表，依赖方向始终是 plugin → starter。
 * <p>
 * <b>{@code sys_tenant} 已在租户内置忽略表清单内</b>，查询不会被行级过滤改写；
 * 且本方法在定时任务线程里执行（无租户上下文），正好也是全表视角。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class SysTenantIdProvider implements TenantIdProvider {

    private final SysTenantMapper tenantMapper;

    public SysTenantIdProvider(SysTenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public List<Long> allTenantIds() {
        LambdaQueryWrapper<SysTenant> query = new LambdaQueryWrapper<>();
        query.select(SysTenant::getId);
        return tenantMapper.selectList(query).stream()
                .map(SysTenant::getId)
                .filter(Objects::nonNull)
                .toList();
    }
}

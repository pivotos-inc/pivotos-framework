package com.pivotos.starter.job.retention;

import java.util.List;

/**
 * 租户 ID 枚举 SPI：留存清理需要「逐个租户」执行，先得知道有哪些租户。
 * <p>
 * 为什么必须由 starter 定义、插件实现：ArchUnit A3 禁止 starter 依赖 plugin，
 * 而租户表 {@code sys_tenant} 的实体在 plugin-system。由 starter 定接口、plugin 提供实现，
 * 各插件的清理任务就都能拿到租户列表，且依赖方向始终是 plugin → starter。
 * <p>
 * 无实现时（单租户部署、未引入 plugin-system）逐租户清理退化为「只清理无租户上下文的数据」，
 * 由调用方决定行为，接口本身不抛异常。
 *
 * @author PivotOS
 * @since 3.0.0
 */
public interface TenantIdProvider {

    /**
     * 全部租户 ID（不含 null；无租户时返回空列表）。
     */
    List<Long> allTenantIds();
}

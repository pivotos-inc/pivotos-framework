package com.pivotos.starter.job.retention;

import com.pivotos.starter.core.context.TenantContext;

import java.util.List;

/**
 * 留存清理模板：把「校验 → 取主键 → 删除 → 出报告」这条链收敛到一处，
 * 各插件只提供 {@link RetentionPurgeOps}，不各自实现安全带。
 * <p>
 * 本类是纯逻辑（不依赖任何持久层），starter-job 也因此无需引入 MyBatis。
 *
 * <h3>两个入口的差别不止是「要不要循环」</h3>
 * <ul>
 *   <li>{@link #runGlobal}：用于 {@code sys_*} 这类<strong>平台共享表</strong>（无 tenant_id 列，
 *       已登记在租户内置忽略表）。<b>刻意不绑定租户上下文</b>——绑了反而会让租户拦截器
 *       尝试追加 {@code tenant_id = ?}。 </li>
 *   <li>{@link #runPerTenant}：用于 {@code msg_*} / {@code ai_*} / {@code flow_his_task}
 *       这类<strong>租户隔离表</strong>。必须逐租户绑定上下文，原因见下。</li>
 * </ul>
 *
 * <h3>为什么必须逐租户（本批最关键的一条）</h3>
 * {@code ColumnTenantStrategy#ignoreTable} 在 {@code TenantContext.get() == null} 时
 * 直接 {@code return true}（「无租户上下文 → 全表放行」的单租户兜底语义）。
 * 定时任务线程里租户上下文<strong>必然为空</strong> → 租户行级过滤被整体跳过 →
 * 一条 {@code DELETE} 就是<strong>静默跨租户全表删除</strong>，且不报错。
 * 既有 {@code OperLogCleanupJob} 没踩到，只因为 {@code sys_oper_log} 恰好没有 tenant_id 列。
 * 所以这里用 {@code ScopedValue.where(TenantContext.KEY, tid)} 逐租户绑定后再执行。
 *
 * @author PivotOS
 * @since 3.0.0
 */
public final class RetentionCleanupTemplate {

    private RetentionCleanupTemplate() {
    }

    /**
     * 清理平台共享表（无租户维度）。
     */
    public static RetentionReport runGlobal(RetentionProperties props, String table, int days,
                                            RetentionPurgeOps ops) {
        return run(props, table, days, 0, ops);
    }

    /**
     * 逐租户清理租户隔离表。
     *
     * @param tenants 租户 ID 来源；为 null 或空列表时返回「0 租户」的报告（不报错）。
     *                单租户部署下没有租户记录是常态，此时该表的数据本身也带租户列，
     *                调用方需自行决定是否额外清理「无租户的行」——本模板不替业务做这个假设。
     */
    public static RetentionReport runPerTenant(RetentionProperties props, String table, int days,
                                               TenantIdProvider tenants, RetentionPurgeOps ops) {
        Acc acc = new Acc();
        if (!props.isEnabled()) {
            return finish(props, table, days, acc, 0, System.currentTimeMillis());
        }
        validate(props, table, days);
        List<Long> ids = tenants == null ? List.of() : tenants.allTenantIds();
        long start = System.currentTimeMillis();
        for (Long tenantId : ids) {
            if (tenantId == null) {
                continue;
            }
            // ScopedValue 不允许绑定 null，上面已过滤
            ScopedValue.where(TenantContext.KEY, tenantId).run(() -> purge(props, ops, acc));
            acc.tenants++;
        }
        return finish(props, table, days, acc, acc.tenants, start);
    }

    private static RetentionReport run(RetentionProperties props, String table, int days,
                                       int tenantCount, RetentionPurgeOps ops) {
        Acc acc = new Acc();
        if (!props.isEnabled()) {
            return finish(props, table, days, acc, tenantCount, System.currentTimeMillis());
        }
        validate(props, table, days);
        long start = System.currentTimeMillis();
        purge(props, ops, acc);
        return finish(props, table, days, acc, tenantCount, start);
    }

    /**
     * 配置红线校验：<strong>拒绝执行并抛异常</strong>，不静默降级。
     * <p>保留天数低于硬下限（尤其配成 0）的语义是全表清空；单次上限小于 1 会让
     * 「先取后删」退化成永不删除。这两种配置错误都必须响，否则运维看到的是
     * 「任务一直成功，表一直涨」或「任务一直成功，数据没了」。
     */
    private static void validate(RetentionProperties props, String table, int days) {
        if (days < props.getMinRetentionDays()) {
            throw new IllegalArgumentException(String.format(
                    "留存策略拒绝执行：%s 保留天数 %d 低于硬下限 %d（配成 0 等于全表清空，已拦截）",
                    table, days, props.getMinRetentionDays()));
        }
        if (props.getMaxDeletePerRun() < 1) {
            throw new IllegalArgumentException(String.format(
                    "留存策略拒绝执行：max-delete-per-run=%d 非法，必须 ≥ 1",
                    props.getMaxDeletePerRun()));
        }
    }

    /** 单次清理动作：取主键 →（非演练才删）。 */
    private static void purge(RetentionProperties props, RetentionPurgeOps ops, Acc acc) {
        RetentionIdBatch batch = ops.loadExpiredIds(props.getMaxDeletePerRun());
        acc.matched += batch.total();
        if (!batch.ids().isEmpty()) {
            if (props.isDryRun()) {
                acc.truncated = acc.truncated || batch.total() > batch.ids().size();
            } else {
                acc.deleted += ops.deleteByIds(batch.ids());
                acc.truncated = acc.truncated || batch.total() > batch.ids().size();
            }
        }
    }

    private static RetentionReport finish(RetentionProperties props, String table, int days,
                                          Acc acc, int tenantCount, long start) {
        return new RetentionReport(table, days, props.isDryRun(), !props.isEnabled(),
                tenantCount, acc.matched, acc.deleted, acc.truncated,
                Math.max(0, System.currentTimeMillis() - start));
    }

    /**
     * lambda 内累加用（Java 不允许在 lambda 里改局部变量，故用可变容器）。
     */
    private static final class Acc {
        long matched;
        long deleted;
        int tenants;
        boolean truncated;
    }
}

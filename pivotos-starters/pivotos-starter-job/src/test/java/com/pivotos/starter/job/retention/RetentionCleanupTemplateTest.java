package com.pivotos.starter.job.retention;

import com.pivotos.starter.core.context.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 留存清理模板测试（v3.1.0 P1-B1）。
 * <p>
 * 本类是<strong>纯逻辑测试、不连数据库</strong>：安全带（硬下限 / 单次上限 / dry-run / 总开关）
 * 与「逐租户绑定」都是模板自己的职责，用假 {@link RetentionPurgeOps} 即可完整验证；
 * 真实 SQL 由各插件 Mapper 与 {@code scripts/e2e/retention_cleanup_test.py} 覆盖。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@DisplayName("RetentionCleanupTemplate")
class RetentionCleanupTemplateTest {

    private static RetentionProperties props() {
        return new RetentionProperties();
    }

    /** 记录调用的假持久层：同时记录「每次被调用时看到的租户上下文」。 */
    private static final class FakeOps implements RetentionPurgeOps {

        final List<Long> tenantsSeen = new ArrayList<>();
        int loadCalls;
        int deleteCalls;
        int requestedSize;
        long total = 3;
        int recordCount = 3;

        @Override
        public RetentionIdBatch loadExpiredIds(int size) {
            loadCalls++;
            requestedSize = size;
            tenantsSeen.add(TenantContext.get());
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < recordCount; i++) {
                ids.add((long) (i + 1));
            }
            return new RetentionIdBatch(ids, total);
        }

        @Override
        public int deleteByIds(List<Long> ids) {
            deleteCalls++;
            return ids.size();
        }
    }

    // ============ 全局表（平台共享表，无租户维度） ============

    @Test
    @DisplayName("G1 全局清理正常执行：按 max-delete-per-run 取数、删除、出报告")
    void g1_globalCleanupRunsAndReports() {
        FakeOps ops = new FakeOps();
        RetentionReport report = RetentionCleanupTemplate.runGlobal(props(), "sys_login_log", 180, ops);

        assertThat(report.skipped()).as("默认总开关为开").isFalse();
        assertThat(report.matched()).isEqualTo(3);
        assertThat(report.deleted()).isEqualTo(3);
        assertThat(report.truncated()).isFalse();
        assertThat(report.tenants()).as("全局表无租户维度").isZero();
        assertThat(ops.deleteCalls).isEqualTo(1);
        assertThat(ops.requestedSize).as("取数尺寸应来自 max-delete-per-run").isEqualTo(5000);
    }

    @Test
    @DisplayName("G2 总开关关闭：整轮跳过，且一次都不查库")
    void g2_disabledSkipsEverything() {
        RetentionProperties props = props();
        props.setEnabled(false);
        FakeOps ops = new FakeOps();

        RetentionReport report = RetentionCleanupTemplate.runGlobal(props, "sys_login_log", 180, ops);

        assertThat(report.skipped()).isTrue();
        assertThat(report.matched()).isZero();
        assertThat(report.deleted()).isZero();
        assertThat(ops.loadCalls).as("跳过时不应产生任何查询").isZero();
        assertThat(ops.deleteCalls).isZero();
    }

    @Test
    @DisplayName("G3 dry-run：照常统计待删量，但一次都不真删")
    void g3_dryRunCountsButNeverDeletes() {
        RetentionProperties props = props();
        props.setDryRun(true);
        FakeOps ops = new FakeOps();

        RetentionReport report = RetentionCleanupTemplate.runGlobal(props, "sys_login_log", 180, ops);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.matched()).as("演练也要算出待删量，否则试算没有意义").isEqualTo(3);
        assertThat(report.deleted()).isZero();
        assertThat(ops.deleteCalls).as("演练绝不能真删").isZero();
    }

    @Test
    @DisplayName("G4 保留天数低于硬下限（尤其 0 天）：拒绝执行并抛异常，不静默全表清空")
    void g4_retentionDaysBelowFloorIsRejected() {
        RetentionProperties props = props();
        FakeOps ops = new FakeOps();

        assertThatThrownBy(() -> RetentionCleanupTemplate.runGlobal(props, "sys_login_log", 0, ops))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝执行")
                .hasMessageContaining("全表清空");
        assertThat(ops.loadCalls).as("拒绝执行前不应查库").isZero();
    }

    @Test
    @DisplayName("G5 单次上限非法（<1）：拒绝执行并抛异常")
    void g5_invalidMaxDeletePerRunIsRejected() {
        RetentionProperties props = props();
        props.setMaxDeletePerRun(0);
        FakeOps ops = new FakeOps();

        assertThatThrownBy(() -> RetentionCleanupTemplate.runGlobal(props, "sys_login_log", 180, ops))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-delete-per-run");
    }

    @Test
    @DisplayName("G6 一轮删不完：只删取到的那批，并标记 truncated")
    void g6_partialRunIsMarkedTruncated() {
        FakeOps ops = new FakeOps();
        ops.total = 10;
        ops.recordCount = 5;

        RetentionReport report = RetentionCleanupTemplate.runGlobal(props(), "sys_login_log", 180, ops);

        assertThat(report.matched()).isEqualTo(10);
        assertThat(report.deleted()).as("本轮只删取到的 5 条").isEqualTo(5);
        assertThat(report.truncated()).as("总数大于本批 → 标记未删完，交给下一轮").isTrue();
    }

    // ============ 租户表（必须逐租户） ============

    @Test
    @DisplayName("T1 逐租户执行：每个租户都在自己的上下文里被查询（防跨租户全表删除的核心断言）")
    void t1_eachTenantRunsInItsOwnContext() {
        FakeOps ops = new FakeOps();
        TenantIdProvider tenants = () -> List.of(7L, 9L, 12L);

        RetentionReport report = RetentionCleanupTemplate.runPerTenant(props(), "msg_message", 180, tenants, ops);

        assertThat(report.tenants()).isEqualTo(3);
        assertThat(ops.tenantsSeen)
                .as("每次查询都必须看到各自的租户上下文——看不到就是「无上下文放行 → 跨租户全表删除」")
                .containsExactly(7L, 9L, 12L);
        assertThat(ops.loadCalls).isEqualTo(3);
        assertThat(ops.deleteCalls).isEqualTo(3);
        assertThat(report.deleted()).isEqualTo(9);
    }

    @Test
    @DisplayName("T2 租户清单里的 null 被跳过，不影响其余租户")
    void t2_nullTenantIdIsSkipped() {
        FakeOps ops = new FakeOps();
        List<Long> withNull = new ArrayList<>();
        withNull.add(5L);
        withNull.add(null);
        withNull.add(8L);
        TenantIdProvider tenants = () -> withNull;

        RetentionReport report = RetentionCleanupTemplate.runPerTenant(props(), "msg_message", 180, tenants, ops);

        assertThat(report.tenants()).isEqualTo(2);
        assertThat(ops.tenantsSeen).as("ScopedValue 不允许绑定 null，必须过滤后跳过").containsExactly(5L, 8L);
    }

    @Test
    @DisplayName("T3 拿不到租户枚举实现（SPI 缺失）：退化为 0 租户且不碰数据")
    void t3_missingTenantProviderDegradesToNoOp() {
        FakeOps ops = new FakeOps();

        RetentionReport report = RetentionCleanupTemplate.runPerTenant(props(), "msg_message", 180, null, ops);

        assertThat(report.tenants()).isZero();
        assertThat(report.deleted()).isZero();
        assertThat(ops.loadCalls).as("拿不到租户清单就不要碰数据").isZero();
    }

    @Test
    @DisplayName("T4 空租户列表：同样零执行")
    void t4_emptyTenantListRunsNothing() {
        FakeOps ops = new FakeOps();
        TenantIdProvider tenants = () -> List.of();

        RetentionReport report = RetentionCleanupTemplate.runPerTenant(props(), "msg_message", 180, tenants, ops);

        assertThat(report.tenants()).isZero();
        assertThat(ops.loadCalls).isZero();
    }

    @Test
    @DisplayName("T5 执行结束后租户上下文不泄漏（ScopedValue 结构化作用域）")
    void t5_tenantContextDoesNotLeakAfterRun() {
        FakeOps ops = new FakeOps();
        TenantIdProvider tenants = () -> List.of(42L);

        RetentionCleanupTemplate.runPerTenant(props(), "msg_message", 180, tenants, ops);

        assertThat(TenantContext.get())
                .as("run 结束必须自动解绑；泄漏会污染同一线程上的后续逻辑")
                .isNull();
    }

    @Test
    @DisplayName("T6 租户维度同样受保留天数硬下限保护")
    void t6_tenantPathIsAlsoGuardedByRetentionFloor() {
        FakeOps ops = new FakeOps();
        TenantIdProvider tenants = () -> List.of(1L);
        RetentionProperties props = props();
        props.setMinRetentionDays(30);

        assertThatThrownBy(() -> RetentionCleanupTemplate.runPerTenant(props, "msg_message", 7, tenants, ops))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝执行");
        assertThat(ops.loadCalls).isZero();
    }

    @Test
    @DisplayName("T7 报告摘要含表名 / 天数 / 删除数，供日志与排障使用")
    void t7_summaryCarriesKeyFields() {
        FakeOps ops = new FakeOps();
        String summary = RetentionCleanupTemplate.runGlobal(props(), "sys_job_log", 180, ops).summary();

        assertThat(summary).contains("sys_job_log").contains("days=180").contains("deleted=3");
    }

    @Test
    @DisplayName("T8 跳过态的摘要明确写 SKIPPED，不与「删了 0 条」混淆")
    void t8_skippedSummaryIsUnambiguous() {
        RetentionProperties props = props();
        props.setEnabled(false);

        assertThat(RetentionCleanupTemplate.runGlobal(props, "sys_job_log", 180, new FakeOps()).summary())
                .contains("SKIPPED");
    }
}

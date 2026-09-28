package com.pivotos.starter.tenant.config.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2（S113）内置租户忽略表契约锁。
 * <p>
 * 背景：S14 之后新建的 sys_ 平台表若未登记进 {@code BUILTIN_IGNORE_TABLES}，租户启用时
 * 行级过滤会追加 {@code tenant_id = ?}——这些表根本没有该列，查询直接报错并统一返回 1500（产品级 bug）。
 * <p>
 * 本类锁死两件事：
 * <ol>
 *   <li>已核对确认的 4 张表必须在忽略表内（防止后续重构误删）；</li>
 *   <li>{@code sys_file} 必须**不在**忽略表内——它是 S25 起刻意设计的租户隔离表
 *       （含 tenant_id 行级隔离列 + idx_tenant_create 索引 + FileServiceImpl 显式写入），
 *       「无脑把 sys_ 表全加进忽略表」会静默关掉文件表的租户隔离。</li>
 * </ol>
 */
class TenantIgnoreTablesTest {

    /** W2 补齐的四张（均无 tenant_id 列，S113 开工简报 §4 逐表核对结论） */
    private static final List<String> W2_COMPLETED =
            List.of("sys_job", "sys_coding_session", "sys_gen_table", "sys_gen_table_column");

    @Test
    @DisplayName("W2：S14 后新建的四张 sys_ 平台表必须在内置忽略表内")
    void completedTablesMustBeIgnored() {
        assertThat(TenantProperties.BUILTIN_IGNORE_TABLES).containsAll(W2_COMPLETED);
    }

    @Test
    @DisplayName("反例锁：sys_file 是租户隔离表，不得进入内置忽略表")
    void sysFileMustKeepTenantIsolation() {
        assertThat(TenantProperties.BUILTIN_IGNORE_TABLES).doesNotContain("sys_file");
    }

    @Test
    @DisplayName("S14 既有基线忽略表未被本次改动破坏")
    void legacyBaselineKept() {
        assertThat(TenantProperties.BUILTIN_IGNORE_TABLES).contains(
                "sys_dept", "sys_post", "sys_user", "sys_role", "sys_menu",
                "sys_dict_type", "sys_dict_data", "sys_config",
                "sys_login_log", "sys_oper_log", "sys_notice", "sys_job_log",
                "sys_tenant", "sys_tenant_package", "flyway_schema_history");
    }

    @Test
    @DisplayName("effectiveIgnoreTables 为内置与追加的并集，且不污染内置集合")
    void effectiveIgnoreTablesIsUnion() {
        TenantProperties props = new TenantProperties();
        props.setIgnoreTables(List.of("custom_shared_table"));

        Set<String> effective = props.effectiveIgnoreTables();
        assertThat(effective).contains("custom_shared_table").containsAll(W2_COMPLETED);
        // 内置集合不可被实例配置污染（静态常量，追加语义只在 effective 层生效）
        assertThat(TenantProperties.BUILTIN_IGNORE_TABLES).doesNotContain("custom_shared_table");
    }
}

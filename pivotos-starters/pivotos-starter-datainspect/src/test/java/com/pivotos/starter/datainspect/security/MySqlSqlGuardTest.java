package com.pivotos.starter.datainspect.security;

import com.pivotos.starter.datainspect.api.enums.DataInspectErrorCode;
import com.pivotos.starter.datainspect.api.security.GuardContext;
import com.pivotos.starter.datainspect.api.security.GuardResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 安全闸门单测：<b>负例是本能力的生命线</b>，每条红线都要有对应断言。
 *
 * <p>口径：闸门硬生效，与权限无关——超管（*:*）同样走这里。
 */
class MySqlSqlGuardTest {

    private final MySqlSqlGuard guard = new MySqlSqlGuard(4000);

    private GuardContext context() {
        return base().build();
    }

    private GuardContext.GuardContextBuilder base() {
        return GuardContext.builder()
                .maxRows(200)
                .hardMaxRows(1000)
                .tableWhitelist(Set.of("sys_user", "biz_order"))
                .allowAllTables(false)
                .tenantId(null)
                .tenantIdColumn("tenant_id")
                .tenantIgnoreTables(Set.of("sys_user"))
                .forceTenantScope(true)
                .tableHasTenantColumn(null);
    }

    // ---------- 负例：写语句 ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE sys_user",
            "DELETE FROM sys_user",
            "UPDATE sys_user SET status = '1'",
            "INSERT INTO sys_user (id) VALUES (1)",
            "ALTER TABLE sys_user ADD COLUMN x varchar(10)",
            "TRUNCATE TABLE sys_user",
            "GRANT ALL ON *.* TO 'root'@'%'",
            "CREATE TABLE t (id bigint)",
            "REPLACE INTO sys_user (id) VALUES (1)",
    })
    void 写语句一律拒绝(String sql) {
        GuardResult result = guard.inspect(sql, context());
        assertFalse(result.isPassed(), sql + " 应被拒绝");
        // 解析失败的语句同样按拒绝处理（保守默认），因此只校验落在 SQL_* 拒绝码上
        assertTrue(result.getRejectCode().startsWith("SQL_"), result.getRejectCode());
    }

    @Test
    void 分号堆叠拒绝() {
        GuardResult result = guard.inspect("SELECT * FROM sys_user LIMIT 1; DROP TABLE sys_user", context());
        assertFalse(result.isPassed());
        assertEquals(DataInspectErrorCode.SQL_MULTI_STATEMENT.name(), result.getRejectCode());
    }

    @Test
    void 可执行注释被中和而非放行() {
        // MySQL 可执行注释（/*! ... */）是最常见的绕过形态：MySQL 端会执行注释内片段。
        // 本闸门口径是「剥离注释后再解析」——绕过内容被吃掉，剩下的语句必须是干净的 SELECT。
        GuardResult result = guard.inspect("SELECT 1 /*! ,(DELETE FROM sys_user) */ FROM sys_user", context());
        assertTrue(result.isPassed(), "剥离后是干净的单表 SELECT，应放行");
        String normalized = result.getNormalizedSql().toUpperCase();
        assertFalse(normalized.contains("DELETE"), normalized);
        assertFalse(normalized.contains("/*!"), normalized);

        // 保守口径：原始文本里出现分号一律按多语句拒绝（即便它在注释内），
        // 宁可误杀也不赌注释解析的完备性
        GuardResult stacked = guard.inspect(
                "SELECT * FROM sys_user /*! ; DROP TABLE sys_user */", context());
        assertFalse(stacked.isPassed());
        assertEquals(DataInspectErrorCode.SQL_MULTI_STATEMENT.name(), stacked.getRejectCode());
    }

    @Test
    void 井号注释拼接拒绝() {
        GuardResult result = guard.inspect("SELECT * FROM sys_user # \n; DROP TABLE sys_user", context());
        assertFalse(result.isPassed());
    }

    @Test
    void 注释剥离后为空拒绝() {
        GuardResult result = guard.inspect("/* nothing */", context());
        assertFalse(result.isPassed());
    }

    @Test
    void 非白名单表拒绝() {
        GuardResult result = guard.inspect("SELECT * FROM sys_role", context());
        assertFalse(result.isPassed());
        assertEquals(DataInspectErrorCode.SQL_TABLE_FORBIDDEN.name(), result.getRejectCode());
    }

    @Test
    void 系统库拒绝() {
        GuardResult result = guard.inspect("SELECT * FROM information_schema.tables", context());
        assertFalse(result.isPassed());
        assertEquals(DataInspectErrorCode.SQL_TABLE_FORBIDDEN.name(), result.getRejectCode());
    }

    @Test
    void 危险函数拒绝() {
        GuardResult sleep = guard.inspect("SELECT * FROM sys_user WHERE SLEEP(10) = 0", context());
        assertFalse(sleep.isPassed());
        assertEquals(DataInspectErrorCode.SQL_FUNCTION_FORBIDDEN.name(), sleep.getRejectCode());

        GuardResult benchmark = guard.inspect("SELECT BENCHMARK(10000000, MD5('x')) FROM sys_user", context());
        assertFalse(benchmark.isPassed());

        GuardResult loadFile = guard.inspect("SELECT LOAD_FILE('/etc/passwd') FROM sys_user", context());
        assertFalse(loadFile.isPassed());
    }

    @Test
    void union与多表拒绝() {
        GuardResult union = guard.inspect(
                "SELECT id FROM sys_user UNION SELECT id FROM biz_order", context());
        assertFalse(union.isPassed());

        GuardResult join = guard.inspect(
                "SELECT u.id FROM sys_user u JOIN biz_order o ON o.user_id = u.id", context());
        assertFalse(join.isPassed(), "多表暂不支持，避免租户改写歧义");
    }

    @Test
    void into与空语句拒绝() {
        assertFalse(guard.inspect("", context()).isPassed());
        assertFalse(guard.inspect("SELECT * FROM sys_user INTO OUTFILE '/tmp/x'", context()).isPassed());
    }

    // ---------- 正例：注入 / 收紧 / 租户改写 ----------

    @Test
    void 无LIMIT时自动注入() {
        GuardResult result = guard.inspect("SELECT * FROM sys_user", context());
        assertTrue(result.isPassed());
        assertEquals(200, result.getEffectiveMaxRows());
        assertTrue(result.getNormalizedSql().toUpperCase().contains("LIMIT 200"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("已自动注入 LIMIT 200")));
    }

    @Test
    void 超限LIMIT被收紧到硬上限() {
        // 请求 5000 行 > 硬上限 1000 → 收紧到 1000
        GuardResult result = guard.inspect("SELECT * FROM sys_user LIMIT 5000", base().maxRows(5000).build());
        assertTrue(result.isPassed());
        assertTrue(result.getNormalizedSql().toUpperCase().contains("LIMIT 1000"), result.getNormalizedSql());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("已收紧")));

        // 请求 99999 行但默认页大小 200 → 收紧到 200（取 min(请求值, 硬上限)）
        GuardResult byPageSize = guard.inspect("SELECT * FROM sys_user LIMIT 99999", context());
        assertTrue(byPageSize.getNormalizedSql().toUpperCase().contains("LIMIT 200"), byPageSize.getNormalizedSql());
    }

    @Test
    void 未超限的LIMIT保留() {
        GuardResult result = guard.inspect("SELECT * FROM sys_user LIMIT 10", context());
        assertTrue(result.isPassed());
        assertTrue(result.getNormalizedSql().toUpperCase().contains("LIMIT 10"));
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void 租户改写对非忽略表生效() {
        GuardResult result = guard.inspect("SELECT * FROM biz_order WHERE status = 1", base()
                .tenantId(10086L)
                .build());
        assertTrue(result.isPassed());
        assertTrue(result.getNormalizedSql().contains("tenant_id = 10086"), result.getNormalizedSql());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("已按租户改写")));
    }

    @Test
    void 租户改写不应用于忽略表() {
        GuardResult result = guard.inspect("SELECT * FROM sys_user", base().tenantId(10086L).build());
        assertTrue(result.isPassed());
        assertFalse(result.getNormalizedSql().contains("tenant_id"), result.getNormalizedSql());
    }

    @Test
    void 租户改写在无租户上下文时不追加() {
        GuardResult result = guard.inspect("SELECT * FROM biz_order", context());
        assertTrue(result.isPassed());
        assertFalse(result.getNormalizedSql().contains("tenant_id"));
    }

    @Test
    void 表无租户列时不追加条件() {
        GuardResult result = guard.inspect("SELECT * FROM biz_order", base()
                .tenantId(1L)
                .tableHasTenantColumn(false)
                .build());
        assertTrue(result.isPassed());
        assertFalse(result.getNormalizedSql().contains("tenant_id"));
    }

    @Test
    void 放开全表时不校验白名单() {
        GuardResult result = guard.inspect("SELECT * FROM any_table", base().allowAllTables(true).build());
        assertTrue(result.isPassed());
    }

    @Test
    void 超长语句拒绝() {
        MySqlSqlGuard shortGuard = new MySqlSqlGuard(20);
        assertFalse(shortGuard.inspect("SELECT * FROM sys_user WHERE x = 1", context()).isPassed());
    }

    // ---------- 注释剥离 ----------

    @Test
    void 注释剥离保留字符串内的相似字符() {
        // 字符串字面量里的 -- / # 不是注释，不能被吃掉
        assertEquals("SELECT 'a--b', 'c#d' FROM sys_user",
                MySqlSqlGuard.stripComments("SELECT 'a--b', 'c#d' FROM sys_user"));
        // 块注释（含可执行注释）整体替换为空格
        assertEquals("SELECT 1   FROM sys_user",
                MySqlSqlGuard.stripComments("SELECT 1 /*! x */ FROM sys_user"));
        // 行注释吃到行尾，换行保留
        assertEquals("SELECT 1 \n FROM sys_user",
                MySqlSqlGuard.stripComments("SELECT 1 -- 行注释\n FROM sys_user"));
    }
}

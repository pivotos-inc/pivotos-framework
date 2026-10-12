package com.pivotos.starter.tenant.it;

/**
 * 集成测试的中间件地址默认值 —— **全仓只允许这一处出现 dev 环境的 IP 字面量**（V3.1-H · B5）。
 *
 * <p>为什么需要一个独立的类：CI 的 test job 会起自己的 MySQL / Redis 容器并注入
 * {@code MYSQL_HOST} / {@code REDIS_HOST}，但此前四个测试 yml 与四个租户 IT 类里都
 * **硬编码了 dev 主机的 IP**。后果有两个方向，都很糟：
 * <ol>
 *   <li>GitHub Actions 连不上那台机器 → 测试红灯，于是「CI 的 test job 从未真正通过」；</li>
 *   <li>反过来，若哪天 Runner 能连通这个公网地址 → CI 会把测试数据写进别人正在用的
 *       开发实例，而流水线依然显示通过。这比红灯更危险。</li>
 * </ol>
 *
 * <p>注解属性（{@code @SpringBootTest(properties=...)}）必须是编译期常量，没法调用本类的方法，
 * 所以那里写成 {@code ${PIVOTOS_TEST_MYSQL_HOST:${MYSQL_HOST:175.24.176.176}}} 这样的占位符，
 * 由 Spring 在运行时从 Environment 解析；本类提供的是运行期用 JDBC 直连核对数据落位时
 * 需要的同样地址。**两者必须始终一致**
 * —— 这就是 {@code scripts/ops/check_ci_wiring.py} 里那条「默认值漂移」门禁要守的事。
 */
final class TestDbDefaults {

    /** dev 中间件主机。CI 注入 MYSQL_HOST 后自动让位。 */
    static final String MYSQL_HOST = "175.24.176.176";

    /** dev 中间件端口。CI 注入 MYSQL_PORT 后自动让位。 */
    static final String MYSQL_PORT = "3306";

    private TestDbDefaults() {
    }

    /** 按与 yml 完全相同的优先级解析主机：PIVOTOS_TEST_MYSQL_HOST &gt; MYSQL_HOST &gt; 本地默认值。 */
    static String mysqlHost() {
        return firstNonBlank(System.getenv("PIVOTOS_TEST_MYSQL_HOST"),
                System.getenv("MYSQL_HOST"), MYSQL_HOST);
    }

    /** 同上处的端口解析：PIVOTOS_TEST_MYSQL_PORT &gt; MYSQL_PORT &gt; 本地默认值。 */
    static String mysqlPort() {
        return firstNonBlank(System.getenv("PIVOTOS_TEST_MYSQL_PORT"),
                System.getenv("MYSQL_PORT"), MYSQL_PORT);
    }

    /**
     * 拼出完整的 JDBC URL。
     *
     * @param database 目标库名
     * @param createDatabaseIfNotExist 是否带 {@code createDatabaseIfNotExist=true}：
     *        数据源由 dynamic-datasource 创建时需要（容器里库还没建），
     *        测试自己用裸 JDBC 核对数据落位时**不要**带——多一个参数会导致 URL 字符串
     *        与连接池里的不匹配，进而连到另一个库上去。
     */
    static String jdbcUrl(String database, boolean createDatabaseIfNotExist) {
        String base = "jdbc:mysql://" + mysqlHost() + ":" + mysqlPort() + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        return createDatabaseIfNotExist ? base + "&createDatabaseIfNotExist=true" : base;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        throw new IllegalStateException("至少一个候选值必须非空");
    }
}

package com.pivotos.starter.metrics.jdbc;

import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Druid 连接池指标单测（V3.1-F · P1-B8-1）。
 *
 * <p>存在的理由：本项目连接池是 <b>Druid 而非 HikariCP</b>（启动日志实证），
 * Boot 自带的 {@code hikaricp.*} 因此永远为空。本测试把这条事实钉住——
 * 将来谁把连接池换成 Hikari（或反过来把 Druid 摘了），这里会立刻红，
 * 而不是等到生产上「连接池那一栏没数据」才发现。
 *
 * @author PivotOS Team
 */
class DruidPoolMetricsBinderTest {

    @Test
    void 注入的Druid池被注册为指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DruidDataSource pool = new DruidDataSource();
        pool.setName("it-master");

        new DruidPoolMetricsBinder(pool).bindTo(registry);

        assertEquals(0.0, gauge(registry, DruidPoolMetricsBinder.ACTIVE, "it-master"),
                "未 init 的池活跃连接应为 0");
        // maxActive 是配置值（默认 8），不随运行态变化，用它验证「读的是真池子而不是常数 0」
        assertEquals((double) pool.getMaxActive(), gauge(registry, DruidPoolMetricsBinder.MAX, "it-master"));
        assertNotNull(registry.find(DruidPoolMetricsBinder.IDLE).tag("pool", "it-master").gauge());
        assertNotNull(registry.find(DruidPoolMetricsBinder.WAITING).tag("pool", "it-master").gauge());
        assertNotNull(registry.find(DruidPoolMetricsBinder.CONNECT_ERRORS).tag("pool", "it-master").gauge());
    }

    @Test
    void 池名为空时归为unknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DruidDataSource pool = new DruidDataSource();
        pool.setName("");

        new DruidPoolMetricsBinder(pool).bindTo(registry);

        assertNotNull(registry.find(DruidPoolMetricsBinder.ACTIVE).tag("pool", "unknown").gauge(),
                "空池名必须归为 unknown，不能出现空 tag 值");
    }

    @Test
    void 没有Druid池时不注册任何指标也不抛异常() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 传 null 且 StatManager 全局表为空（单测环境没有已 init 的池）
        new DruidPoolMetricsBinder(null).bindTo(registry);
        assertTrue(registry.getMeters().isEmpty(),
                "拿不到池子就什么都不注册——连接池指标不能变成启动单点");
    }

    @Test
    void tag只带pool维度不含URL() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DruidDataSource pool = new DruidDataSource();
        pool.setName("it-master");
        pool.setUrl("jdbc:mysql://127.0.0.1:3306/pivotos_test");

        new DruidPoolMetricsBinder(pool).bindTo(registry);

        Gauge gauge = registry.find(DruidPoolMetricsBinder.ACTIVE).tag("pool", "it-master").gauge();
        assertNotNull(gauge);
        assertEquals(java.util.Set.of("pool"),
                gauge.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey)
                        .collect(java.util.stream.Collectors.toSet()),
                "本 binder 只应带 pool 一个维度");
        assertNull(registry.find(DruidPoolMetricsBinder.ACTIVE).tag("url", "jdbc:mysql://127.0.0.1:3306/pivotos_test").gauge(),
                "JDBC URL 绝不能当 tag：既是高基数，也泄露主机与库名");
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String pool) {
        Gauge gauge = registry.find(name).tag("pool", pool).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}

package com.pivotos.starter.metrics.jdbc;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Druid 连接池指标（V3.1-F · P1-B8-1）。
 *
 * <h3>为什么必须自研：本项目的连接池是 Druid，不是 HikariCP</h3>
 * Boot 自带的连接池指标是 {@code hikaricp.*}，由 spring-boot-jdbc 的
 * {@code DataSourceMetricsConfiguration} 装配，**只认 HikariCP**。
 * 而本项目走 dynamic-datasource + Druid（启动日志实证：
 * {@code com.alibaba.druid.pool.DruidDataSource {dataSource-1,master} inited}），
 * 于是 {@code hikaricp.*} 一条都不会有——而且<b>不报错、不告警</b>，
 * 表现就是「接了指标，但连接池那一栏永远是空的」。
 * 这一条不查启动日志根本发现不了，故写在这里存档。
 *
 * <h3>取实例的姿势</h3>
 * 优先 {@link DruidDataSourceStatManager#getDruidDataSourceInstances()}（Druid 自己维护的全局注册表，
 * 与 Spring 无关，dynamic-datasource 下多数据源也能全拿到）；
 * 再用注入的 {@link DataSource} 兜底（未 inited 的池不在注册表里）。
 * 两者都拿不到时只 WARN 一次，绝不抛异常——连接池指标不该成为启动单点。
 *
 * <h3>tag</h3>
 * 只有 {@code pool}（数据源名，有限集）；绝不把 JDBC URL 当 tag（含口令与主机信息，
 * 既是高基数也是信息泄露）。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · P1-B8-1）
 */
public class DruidPoolMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(DruidPoolMetricsBinder.class);

    /** 正在使用的连接数（借出未归还） */
    public static final String ACTIVE = "pivotos.datasource.connections.active";
    /** 池中空闲连接数 */
    public static final String IDLE = "pivotos.datasource.connections.idle";
    /** 允许的最大活跃连接数（配置项，用于算使用率） */
    public static final String MAX = "pivotos.datasource.connections.max";
    /** 正在等待拿连接的线程数（>0 说明池子不够用了） */
    public static final String WAITING = "pivotos.datasource.connections.waiting";
    /** 累计建连失败次数（Druid 侧是累计值，用 Gauge 暴露，看趋势即可） */
    public static final String CONNECT_ERRORS = "pivotos.datasource.connections.connect.errors";

    private final DataSource injectedDataSource;

    public DruidPoolMetricsBinder(DataSource injectedDataSource) {
        this.injectedDataSource = injectedDataSource;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        List<DruidDataSource> pools = resolve();
        if (pools.isEmpty()) {
            log.warn("[METRICS] 未发现 DruidDataSource，连接池指标不注册（若实际连接池不是 Druid，属预期）");
            return;
        }
        for (DruidDataSource pool : pools) {
            String name = poolName(pool);
            Gauge.builder(ACTIVE, pool, DruidDataSource::getActiveCount)
                    .tag("pool", name)
                    .description("Druid 连接池正在使用的连接数")
                    .register(registry);
            Gauge.builder(IDLE, pool, DruidDataSource::getPoolingCount)
                    .tag("pool", name)
                    .description("Druid 连接池空闲连接数")
                    .register(registry);
            Gauge.builder(MAX, pool, DruidDataSource::getMaxActive)
                    .tag("pool", name)
                    .description("Druid 连接池允许的最大活跃连接数")
                    .register(registry);
            Gauge.builder(WAITING, pool, DruidDataSource::getWaitThreadCount)
                    .tag("pool", name)
                    .description("等待获取连接的线程数（大于 0 说明连接池偏紧）")
                    .register(registry);
            Gauge.builder(CONNECT_ERRORS, pool, DruidDataSource::getConnectErrorCount)
                    .tag("pool", name)
                    .description("累计建连失败次数")
                    .register(registry);
            log.info("[METRICS] Druid 连接池指标已注册：pool={}", name);
        }
    }

    /** 解析出全部 Druid 池：StatManager 全局表优先，注入的 DataSource 兜底 */
    List<DruidDataSource> resolve() {
        List<DruidDataSource> pools = new ArrayList<>();
        try {
            // 返回类型是 Set<DataSourceMonitorable>（druid 1.2.28），不是 Set<DruidDataSource>，
            // 且该接口不含 getName()；故这里按 Object 遍历、只认真正的 DruidDataSource 实例。
            Set<?> instances = DruidDataSourceStatManager.getDruidDataSourceInstances();
            if (instances != null) {
                for (Object instance : instances) {
                    if (instance instanceof DruidDataSource druid) {
                        pools.add(druid);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[METRICS] Druid 全局注册表读取失败，改用注入的 DataSource：{}", e.getMessage());
        }
        if (injectedDataSource instanceof DruidDataSource druid && !pools.contains(druid)) {
            pools.add(druid);
        }
        return pools;
    }

    private static String poolName(DruidDataSource pool) {
        String name = pool.getName();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }
}

package com.pivotos.monitor.service.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;

/**
 * 数据库探活（关键依赖）：取一次连接跑 {@code SELECT 1}。
 *
 * <h3>超时怎么吃在自己身上</h3>
 * 用 JDBC 标准的 {@code Statement#setQueryTimeout}（按秒取整，最少 1 秒）——这样慢查询/网络挂死
 * 由驱动侧中断，而不是把探针线程拖死。
 * <p><b>已知边界</b>：JDBC 没有「获取连接」的超时接口（只有 {@code DataSource#setLoginTimeout}，
 * 多数连接池不实现），连接池被耗尽时 {@code getConnection()} 可能超出预算——这是 Java 侧的通用限制，
 * 报告里如实记录，不假装能兜住。
 *
 * <h3>为什么不落 URL</h3>
 * 明细只给「连接池实现类名 + 数据库产品名（mysql）」，<b>刻意不带 URL / 主机 / 端口 / 账号</b>：
 * 连接串里可能带口令或内网地址，探活响应一旦被匿名拿到就是信息泄漏。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@RequiredArgsConstructor
public class DbHealthCheckStep implements HealthCheckStep {

    public static final String NAME = "db";

    private final ObjectProvider<DataSource> dataSourceProvider;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public boolean absent() {
        return dataSourceProvider.getIfAvailable() == null;
    }

    @Override
    public HealthCheckResult probe(int timeoutMs) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return HealthCheckResult.absent(NAME, true, "未装配 DataSource");
        }
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(Math.max(1, (int) Math.ceil(timeoutMs / 1000.0)));
            statement.execute();
            return HealthCheckResult.builder()
                    .name(NAME)
                    .critical(true)
                    .up(true)
                    .implementation(dataSource.getClass().getSimpleName())
                    .configuredType(productName(connection))
                    .durationMs(Elapsed.since(start))
                    .build();
        } catch (Exception e) {
            return HealthCheckResult.error(NAME, true, "数据库探活失败（连接不可用或超时）", e, Elapsed.since(start));
        }
    }

    private static String productName(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product == null ? "unknown" : product.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            // 元数据拿不到不影响「连通」这个核心结论
            return "unknown";
        }
    }
}

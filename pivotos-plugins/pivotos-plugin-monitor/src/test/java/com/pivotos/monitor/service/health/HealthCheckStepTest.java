package com.pivotos.monitor.service.health;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisSingle;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB / Redis 两个检查项的探活行为：可用 → UP，不可用 → DOWN + 原因，缺席 → 跳过。
 *
 * <p>这两个是系统的关键依赖，判错直接牵动 Nginx 摘流，必须逐条钉死。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class HealthCheckStepTest {

    // ==================== DB ====================

    @Test
    void dbUpReportsPoolAndProductWithoutUrl() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);

        HealthCheckResult result = new DbHealthCheckStep(provider(dataSource)).probe(3000);

        assertTrue(result.isUp());
        assertTrue(result.isCritical());
        assertEquals("mysql", result.getConfiguredType());
        assertNotNull(result.getImplementation(), "实现名必须来自运行态 DataSource，不写死");
        assertFalse(result.getImplementation().isBlank());
        assertFalse(result.toString().contains("jdbc:"), "任何探活结果都不得带连接串");
    }

    @Test
    void dbQueryTimeoutFollowsProbeBudget() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);

        new DbHealthCheckStep(provider(dataSource)).probe(700);

        // JDBC 只有「秒」粒度：700ms → ceil = 1s；预算必须真的传下去，否则慢查询会拖死探针线程
        verify(statement).setQueryTimeout(1);
    }

    @Test
    void dbFailureMarksDownWithExceptionClassOnly() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(dataSource.getConnection()).thenReturn(connection);
        doThrow(new SQLException("Access denied for user 'x'@'10.0.0.9'")).when(statement).execute();

        HealthCheckResult result = new DbHealthCheckStep(provider(dataSource)).probe(3000);

        assertFalse(result.isUp());
        assertEquals("SQLException", result.getError());
        assertFalse(result.getReason().contains("Access denied"), "异常明细里不重复落原始 message");
    }

    @Test
    void dbAbsentWhenNoDataSource() {
        HealthCheckResult result = new DbHealthCheckStep(provider(null)).probe(3000);

        assertTrue(result.isAbsent());
        assertTrue(result.isCritical(), "缺席不影响固有关键性，但聚合层会跳过它");
    }

    // ==================== Redis ====================

    @Test
    void redisUpWhenPong() {
        RedissonClient client = mock(RedissonClient.class);
        RedisSingle single = mock(RedisSingle.class);
        when(client.getRedisNodes(org.redisson.api.redisnode.RedisNodes.SINGLE)).thenReturn(single);
        when(single.pingAll(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        HealthCheckResult result = new RedisHealthCheckStep(provider(client)).probe(3000);

        assertTrue(result.isUp());
        assertEquals("redisson", result.getImplementation());
        assertEquals("RedisSingle", result.getConfiguredType());
    }

    @Test
    void redisDownWhenPingFails() {
        RedissonClient client = mock(RedissonClient.class);
        RedisSingle single = mock(RedisSingle.class);
        when(client.getRedisNodes(org.redisson.api.redisnode.RedisNodes.SINGLE)).thenReturn(single);
        when(single.pingAll(3000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        HealthCheckResult result = new RedisHealthCheckStep(provider(client)).probe(3000);

        assertFalse(result.isUp());
        assertTrue(result.isFallback());
        assertNotNull(result.getReason());
    }

    @Test
    void redisDownButNeverThrowsWhenNodeGroupUnavailable() {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getRedisNodes(any())).thenThrow(new IllegalStateException("Redis is in cluster mode"));

        HealthCheckResult result = new RedisHealthCheckStep(provider(client)).probe(3000);

        assertFalse(result.isUp(), "拿不到节点组说明当前形态未被覆盖，宁可报不可用也不能假装健康");
    }

    @Test
    void redisAbsentWhenNoRedisson() {
        HealthCheckResult result = new RedisHealthCheckStep(provider(null)).probe(3000);

        assertTrue(result.isAbsent());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}

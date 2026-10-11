package com.pivotos.monitor.service.health;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.BaseRedisNodes;
import org.redisson.api.redisnode.RedisNodes;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

/**
 * Redis 探活（关键依赖）：{@code Redisson} 节点组 {@code pingAll(timeout)}。
 *
 * <h3>为什么按「节点组」而不是「单实例」</h3>
 * 本项目现在跑的是 Redisson 单节点（口径见《项目记忆 §3》），但探针不该假设部署形态：这里按
 * single → master-slave → cluster → sentinel 顺序取第一个拿得到的节点组，再对它做 {@code pingAll}。
 * 将来换成哨兵/集群不用改代码。
 *
 * <h3>为什么 Redis 算关键依赖</h3>
 * Sa-Token 的会话 DAO 落在 Redis（{@code SaTokenDaoForRedisson}），Redis 不可用时登录态读写即失败，
 * 服务事实上已不可用 → 判 {@code 503} 是诚实的结论。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
@RequiredArgsConstructor
public class RedisHealthCheckStep implements HealthCheckStep {

    public static final String NAME = "redis";

    private final ObjectProvider<RedissonClient> redissonProvider;

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
        return redissonProvider.getIfAvailable() == null;
    }

    @Override
    public HealthCheckResult probe(int timeoutMs) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return HealthCheckResult.absent(NAME, true, "未装配 RedissonClient");
        }
        long start = System.nanoTime();
        try {
            RedisNodes<?> nodes = pick(client);
            if (nodes == null) {
                return HealthCheckResult.error(NAME, true, "取不到 Redis 节点组（Redisson 形态未覆盖）",
                        null, Elapsed.since(start));
            }
            boolean pong = nodes(client, nodes).pingAll(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            return HealthCheckResult.builder()
                    .name(NAME)
                    .critical(true)
                    .up(pong)
                    .fallback(!pong)
                    .implementation("redisson")
                    .configuredType(nodes.getClazz().getSimpleName())
                    .reason(pong ? null : "PING 未成功（节点不可达或超时）")
                    .durationMs(Elapsed.since(start))
                    .build();
        } catch (Exception e) {
            return HealthCheckResult.error(NAME, true, "Redis 探活失败（连接不可用或超时）", e, Elapsed.since(start));
        }
    }

    /** 当前 Redisson 配置下真正存在的那个节点组 */
    private RedisNodes<?> pick(RedissonClient client) {
        for (RedisNodes<?> candidate : CANDIDATES) {
            try {
                BaseRedisNodes nodes = nodes(client, candidate);
                if (nodes != null) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 该形态不适用于当前配置（如单节点下调 cluster），换下一个
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BaseRedisNodes nodes(RedissonClient client, RedisNodes<?> nodes) {
        return client.getRedisNodes((RedisNodes) nodes);
    }

    private static final RedisNodes<?>[] CANDIDATES = {
            RedisNodes.SINGLE,
            RedisNodes.MASTER_SLAVE,
            RedisNodes.SENTINEL_MASTER_SLAVE,
            RedisNodes.CLUSTER
    };
}

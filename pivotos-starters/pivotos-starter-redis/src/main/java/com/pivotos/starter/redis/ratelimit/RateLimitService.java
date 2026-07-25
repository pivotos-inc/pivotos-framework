package com.pivotos.starter.redis.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

/**
 * 分布式限流器封装（Redisson RRateLimiter，令牌桶）。
 * 典型用法：登录接口防爆破、短信发送频率控制。
 */
public class RateLimitService {

    private final RedissonClient redissonClient;

    public RateLimitService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取一个许可（不等待）
     *
     * @param key          限流维度键，如 rate:login:{username}
     * @param rate         窗口内允许的请求数
     * @param intervalSecs 窗口秒数
     * @return true = 放行，false = 触发限流
     */
    public boolean tryAcquire(String key, long rate, long intervalSecs) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        // setRate 幂等：已设置过会按已有限速生效（Redisson 内部处理）
        limiter.setRate(RateType.OVERALL, rate, intervalSecs, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }
}

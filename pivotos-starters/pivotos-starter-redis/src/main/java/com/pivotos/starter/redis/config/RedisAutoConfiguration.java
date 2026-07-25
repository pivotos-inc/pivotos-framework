package com.pivotos.starter.redis.config;

import com.pivotos.starter.redis.cache.TenantCacheKeyGenerator;
import com.pivotos.starter.redis.checker.RedisRepeatSubmitChecker;
import com.pivotos.starter.redis.ratelimit.RateLimitService;
import com.pivotos.starter.web.checker.RepeatSubmitChecker;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

/**
 * Redis 分布式能力自动配置：
 * Redisson / Lock4j 由各自 starter 自动装配，本类注册框架增强 Bean。
 * 注册 RepeatSubmitChecker 后，starter-web 的幂等拦截器自动激活（条件装配闭环）。
 */
@AutoConfiguration
@EnableCaching
@ConditionalOnBean(RedissonClient.class)
public class RedisAutoConfiguration {

    /**
     * 幂等判定器 Redis 实现（激活 starter-web 的 @RepeatSubmit）
     */
    @Bean
    @ConditionalOnMissingBean(RepeatSubmitChecker.class)
    public RepeatSubmitChecker repeatSubmitChecker(RedissonClient redissonClient) {
        return new RedisRepeatSubmitChecker(redissonClient);
    }

    /**
     * 租户维度缓存 key 生成器
     */
    @Bean
    public TenantCacheKeyGenerator tenantCacheKeyGenerator() {
        return new TenantCacheKeyGenerator();
    }

    /**
     * 分布式限流器
     */
    @Bean
    public RateLimitService rateLimitService(RedissonClient redissonClient) {
        return new RateLimitService(redissonClient);
    }
}

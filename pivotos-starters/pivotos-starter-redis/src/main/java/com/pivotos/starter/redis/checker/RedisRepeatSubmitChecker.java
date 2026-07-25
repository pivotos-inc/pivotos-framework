package com.pivotos.starter.redis.checker;

import com.pivotos.starter.web.checker.RepeatSubmitChecker;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * RepeatSubmitChecker 的 Redis 实现：
 * setIfAbsent + TTL 占位，占位成功=首次请求，占位失败=窗口内重复提交。
 * 本 Bean 注册后，starter-web 的 RepeatSubmitInterceptor 自动装配生效。
 */
public class RedisRepeatSubmitChecker implements RepeatSubmitChecker {

    private final RedissonClient redissonClient;

    public RedisRepeatSubmitChecker(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean isRepeatSubmit(String key, long intervalMillis) {
        RBucket<String> bucket = redissonClient.getBucket(key);
        boolean firstTime = bucket.setIfAbsent("1", Duration.ofMillis(intervalMillis));
        return !firstTime;
    }
}

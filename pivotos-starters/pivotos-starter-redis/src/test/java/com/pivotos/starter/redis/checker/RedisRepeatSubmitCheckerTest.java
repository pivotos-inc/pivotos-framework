package com.pivotos.starter.redis.checker;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.mockito.Mockito;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 幂等 Redis 实现单测（Mock Redisson，不依赖真实 Redis）
 */
class RedisRepeatSubmitCheckerTest {

    @Test
    @SuppressWarnings("unchecked")
    void firstRequestShouldPassAndSecondShouldRepeat() {
        RedissonClient redissonClient = Mockito.mock(RedissonClient.class);
        RBucket<String> bucket = Mockito.mock(RBucket.class);
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        // 第一次占位成功，第二次失败
        when(bucket.setIfAbsent(anyString(), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false);

        RedisRepeatSubmitChecker checker = new RedisRepeatSubmitChecker(redissonClient);
        assertFalse(checker.isRepeatSubmit("repeat_submit:u1:POST:/demo:abc", 5000));
        assertTrue(checker.isRepeatSubmit("repeat_submit:u1:POST:/demo:abc", 5000));
    }
}

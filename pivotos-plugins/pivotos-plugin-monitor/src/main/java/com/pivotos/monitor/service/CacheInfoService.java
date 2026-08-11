package com.pivotos.monitor.service;

import com.pivotos.monitor.domain.vo.CacheInfoVO;
import com.pivotos.monitor.support.RedisInfoParser;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 缓存监控采集（S48 2.3-F6）：Redisson 单节点 INFO + DBSIZE。
 * <p>
 * 用法与 system 插件在线用户会话扫描一致（直接注入 RedissonClient）。
 * 单机部署取 RedisNodes.SINGLE 唯一实例；INFO 解析下沉到 {@link RedisInfoParser} 便于单测。
 */
@Service
@RequiredArgsConstructor
public class CacheInfoService {

    private final RedissonClient redissonClient;

    public CacheInfoVO collect() {
        Map<String, String> info = redissonClient.getRedisNodes(RedisNodes.SINGLE)
                .getInstance()
                .info(RedisNode.InfoSection.ALL);
        long dbSize = redissonClient.getKeys().count();
        return RedisInfoParser.toVO(info, dbSize);
    }
}

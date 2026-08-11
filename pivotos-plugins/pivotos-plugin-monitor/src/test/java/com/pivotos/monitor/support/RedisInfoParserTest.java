package com.pivotos.monitor.support;

import com.pivotos.monitor.domain.vo.CacheInfoVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisInfoParser 纯函数单测：样例取自 Redis 7 INFO 真实输出形态。
 */
class RedisInfoParserTest {

    private Map<String, String> sampleInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("redis_version", "7.2.5");
        info.put("redis_mode", "standalone");
        info.put("os", "Linux 5.15.0 x86_64");
        info.put("arch_bits", "64");
        info.put("process_id", "1234");
        info.put("tcp_port", "6379");
        info.put("uptime_in_seconds", "86400");
        info.put("connected_clients", "12");
        info.put("blocked_clients", "0");
        info.put("used_memory_human", "3.50M");
        info.put("used_memory_peak_human", "4.00M");
        info.put("maxmemory_human", "0B");
        info.put("mem_fragmentation_ratio", "1.25");
        info.put("keyspace_hits", "750");
        info.put("keyspace_misses", "250");
        info.put("db0", "keys=100,expires=20,avg_ttl=3600000");
        info.put("db1", "keys=5,expires=0,avg_ttl=0");
        info.put("cmdstat_get", "calls=900,usec=1800,usec_per_call=2.00,rejected_calls=0,failed_calls=0");
        info.put("cmdstat_set", "calls=100,usec=500,usec_per_call=5.00,rejected_calls=0,failed_calls=0");
        return info;
    }

    @Test
    void toVoParsesBasicSections() {
        CacheInfoVO vo = RedisInfoParser.toVO(sampleInfo(), 105);

        assertThat(vo.getRedisVersion()).isEqualTo("7.2.5");
        assertThat(vo.getRedisMode()).isEqualTo("standalone");
        assertThat(vo.getArchBits()).isEqualTo("64");
        assertThat(vo.getUptimeInSeconds()).isEqualTo(86400);
        assertThat(vo.getConnectedClients()).isEqualTo(12);
        assertThat(vo.getUsedMemoryHuman()).isEqualTo("3.50M");
        assertThat(vo.getMemFragmentationRatio()).isEqualTo(1.25);
        assertThat(vo.getDbSize()).isEqualTo(105);
    }

    @Test
    void toVoComputesHitRate() {
        CacheInfoVO vo = RedisInfoParser.toVO(sampleInfo(), 0);
        // 750 / (750+250) = 75%
        assertThat(vo.getHitRate()).isEqualTo(75.0);
    }

    @Test
    void toVoHitRateZeroWhenNoSamples() {
        Map<String, String> info = sampleInfo();
        info.put("keyspace_hits", "0");
        info.put("keyspace_misses", "0");
        assertThat(RedisInfoParser.toVO(info, 0).getHitRate()).isEqualTo(0);
    }

    @Test
    void keyspaceParsedAndSorted() {
        CacheInfoVO vo = RedisInfoParser.toVO(sampleInfo(), 0);
        assertThat(vo.getKeyspace()).hasSize(2);
        assertThat(vo.getKeyspace().get(0).getDb()).isEqualTo("db0");
        assertThat(vo.getKeyspace().get(0).getKeys()).isEqualTo(100);
        assertThat(vo.getKeyspace().get(0).getExpires()).isEqualTo(20);
        assertThat(vo.getKeyspace().get(0).getAvgTtl()).isEqualTo(3600000);
        assertThat(vo.getKeyspace().get(1).getDb()).isEqualTo("db1");
    }

    @Test
    void commandStatsParsedSortedByCallsDesc() {
        CacheInfoVO vo = RedisInfoParser.toVO(sampleInfo(), 0);
        assertThat(vo.getCommandStats()).hasSize(2);
        assertThat(vo.getCommandStats().get(0).getName()).isEqualTo("get");
        assertThat(vo.getCommandStats().get(0).getCalls()).isEqualTo(900);
        assertThat(vo.getCommandStats().get(0).getUsecPerCall()).isEqualTo(2.00);
        assertThat(vo.getCommandStats().get(1).getName()).isEqualTo("set");
    }

    @Test
    void missingKeysFallBackToZero() {
        CacheInfoVO vo = RedisInfoParser.toVO(Map.of(), 0);
        assertThat(vo.getConnectedClients()).isZero();
        assertThat(vo.getKeyspace()).isEmpty();
        assertThat(vo.getCommandStats()).isEmpty();
        assertThat(vo.getHitRate()).isEqualTo(0);
    }
}

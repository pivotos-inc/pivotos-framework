package com.pivotos.monitor.support;

import com.pivotos.monitor.domain.vo.CacheInfoVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis INFO 解析器（纯函数，可脱离 Spring/Redis 单测）。
 * <p>
 * Redisson {@code RedisSingle.info(InfoSection.ALL)} 返回扁平 Map：
 * server/clients/memory/stats/keyspace/commandstats 各 section 键混在一起，
 * 如 redis_version / connected_clients / used_memory_human / keyspace_hits /
 * db0="keys=5,expires=2,avg_ttl=0" / cmdstat_get="calls=1,usec=8,usec_per_call=8.00,..."。
 */
public final class RedisInfoParser {

    private RedisInfoParser() {
    }

    /** INFO Map + DBSIZE → CacheInfoVO */
    public static CacheInfoVO toVO(Map<String, String> info, long dbSize) {
        CacheInfoVO vo = new CacheInfoVO();
        vo.setRedisVersion(info.get("redis_version"));
        vo.setRedisMode(info.get("redis_mode"));
        vo.setOs(info.get("os"));
        vo.setArchBits(info.get("arch_bits"));
        vo.setProcessId(info.get("process_id"));
        vo.setTcpPort(info.get("tcp_port"));
        vo.setUptimeInSeconds(parseLong(info, "uptime_in_seconds"));

        vo.setConnectedClients((int) parseLong(info, "connected_clients"));
        vo.setBlockedClients((int) parseLong(info, "blocked_clients"));

        vo.setUsedMemoryHuman(info.get("used_memory_human"));
        vo.setUsedMemoryPeakHuman(info.get("used_memory_peak_human"));
        vo.setMaxmemoryHuman(info.get("maxmemory_human"));
        vo.setMemFragmentationRatio(parseDouble(info, "mem_fragmentation_ratio"));

        long hits = parseLong(info, "keyspace_hits");
        long misses = parseLong(info, "keyspace_misses");
        vo.setKeyspaceHits(hits);
        vo.setKeyspaceMisses(misses);
        vo.setHitRate(hits + misses == 0 ? 0 : round2(hits * 100.0 / (hits + misses)));

        vo.setDbSize(dbSize);
        vo.setKeyspace(parseKeyspace(info));
        vo.setCommandStats(parseCommandStats(info));
        return vo;
    }

    /** dbN=keys=..,expires=..,avg_ttl=.. 条目 */
    static List<CacheInfoVO.KeyspaceStat> parseKeyspace(Map<String, String> info) {
        List<CacheInfoVO.KeyspaceStat> list = new ArrayList<>();
        info.forEach((key, value) -> {
            if (!key.matches("db\\d+")) {
                return;
            }
            Map<String, String> kv = splitKv(value);
            CacheInfoVO.KeyspaceStat stat = new CacheInfoVO.KeyspaceStat();
            stat.setDb(key);
            stat.setKeys(parseLong(kv, "keys"));
            stat.setExpires(parseLong(kv, "expires"));
            stat.setAvgTtl(parseLong(kv, "avg_ttl"));
            list.add(stat);
        });
        list.sort(Comparator.comparing(CacheInfoVO.KeyspaceStat::getDb));
        return list;
    }

    /** cmdstat_xxx=calls=..,usec=..,usec_per_call=.. 条目（按调用次数降序） */
    static List<CacheInfoVO.CommandStat> parseCommandStats(Map<String, String> info) {
        List<CacheInfoVO.CommandStat> list = new ArrayList<>();
        info.forEach((key, value) -> {
            if (!key.startsWith("cmdstat_")) {
                return;
            }
            Map<String, String> kv = splitKv(value);
            CacheInfoVO.CommandStat stat = new CacheInfoVO.CommandStat();
            stat.setName(key.substring("cmdstat_".length()));
            stat.setCalls(parseLong(kv, "calls"));
            stat.setUsecPerCall(parseDouble(kv, "usec_per_call"));
            list.add(stat);
        });
        list.sort(Comparator.comparingLong(CacheInfoVO.CommandStat::getCalls).reversed());
        return list;
    }

    /** "a=1,b=2" → Map */
    private static Map<String, String> splitKv(String value) {
        Map<String, String> map = new java.util.HashMap<>();
        if (value == null) {
            return map;
        }
        for (String pair : value.split(",")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return map;
    }

    static long parseLong(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double parseDouble(Map<String, String> map, String key) {
        String v = map.get(key);
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

package com.pivotos.monitor.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 缓存监控快照（S48 2.3-F6）：Redis INFO 关键字段 + DBSIZE + 键空间/命令统计。
 */
@Data
public class CacheInfoVO {

    // ---------- 基本信息 ----------
    private String redisVersion;
    private String redisMode;
    private String os;
    private String archBits;
    private String processId;
    private String tcpPort;
    private long uptimeInSeconds;

    // ---------- 客户端 ----------
    private int connectedClients;
    private int blockedClients;

    // ---------- 内存 ----------
    private String usedMemoryHuman;
    private String usedMemoryPeakHuman;
    private String maxmemoryHuman;
    private double memFragmentationRatio;

    // ---------- 命中率 ----------
    private long keyspaceHits;
    private long keyspaceMisses;
    /** 命中率 %（无命中样本时为 0） */
    private double hitRate;

    // ---------- 键值规模 ----------
    private long dbSize;
    private List<KeyspaceStat> keyspace;

    // ---------- 命令统计 ----------
    private List<CommandStat> commandStats;

    /** 键空间（db0:keys=..,expires=..,avg_ttl=..） */
    @Data
    public static class KeyspaceStat {
        private String db;
        private long keys;
        private long expires;
        private long avgTtl;
    }

    /** 命令统计（cmdstat_xxx） */
    @Data
    public static class CommandStat {
        private String name;
        private long calls;
        private double usecPerCall;
    }
}

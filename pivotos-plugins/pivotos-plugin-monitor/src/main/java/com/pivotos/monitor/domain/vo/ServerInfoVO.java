package com.pivotos.monitor.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 服务监控快照（S48 2.3-F6）。
 * <p>
 * 采集来源：oshi（CPU/内存/磁盘/主机）+ JMX（JVM/平台线程）
 * + jdk.management.VirtualThreadSchedulerMXBean（虚拟线程调度器，JDK 21+）。
 */
@Data
public class ServerInfoVO {

    private Cpu cpu;
    private Mem mem;
    private Jvm jvm;
    private VirtualThreads virtualThreads;
    private Sys sys;
    private List<SysFile> sysFiles;

    /** CPU（百分比为两次采样间隔内的占比） */
    @Data
    public static class Cpu {
        /** 物理核数 */
        private int physicalNum;
        /** 逻辑核数 */
        private int cpuNum;
        /** 用户态使用率 % */
        private double used;
        /** 系统态使用率 % */
        private double sys;
        /** 空闲率 % */
        private double idle;
        /** IO 等待率 % */
        private double wait;
    }

    /** 物理内存（字节） */
    @Data
    public static class Mem {
        private long total;
        private long used;
        private long free;
        /** 使用率 % */
        private double usage;
    }

    /** JVM 信息（内存单位为字节） */
    @Data
    public static class Jvm {
        private String name;
        private String version;
        private String vendor;
        private String home;
        private String startTime;
        private long uptimeMillis;
        private String inputArgs;
        private long heapInit;
        private long heapUsed;
        private long heapCommitted;
        private long heapMax;
        private long nonHeapUsed;
        /** 平台线程数（JMX 不含虚拟线程，虚拟线程见 virtualThreads） */
        private int threadCount;
        private int peakThreadCount;
        private int daemonThreadCount;
    }

    /** 虚拟线程调度器指标（jdk.management.VirtualThreadSchedulerMXBean，JDK 21+） */
    @Data
    public static class VirtualThreads {
        /** 当前 JDK 是否支持该 MXBean */
        private boolean supported;
        /** 调度器并行度（默认=可用处理器数） */
        private int parallelism;
        /** 载体（平台）线程池当前大小 */
        private int poolSize;
        /** 已挂载到载体线程的虚拟线程数 */
        private long mounted;
        /** 排队等待调度的虚拟线程数 */
        private long queued;
    }

    /** 服务器/操作系统信息 */
    @Data
    public static class Sys {
        private String hostName;
        private String ip;
        private String osName;
        private String osVersion;
        private String osArch;
    }

    /** 磁盘分区（字节） */
    @Data
    public static class SysFile {
        /** 挂载点 */
        private String dirName;
        /** 文件系统类型（apfs/ext4...） */
        private String sysTypeName;
        /** 卷名 */
        private String typeName;
        private long total;
        private long used;
        private long free;
        /** 使用率 % */
        private double usage;
    }
}

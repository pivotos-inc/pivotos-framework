package com.pivotos.monitor.service;

import com.pivotos.monitor.domain.vo.ServerInfoVO;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.software.os.OSFileStore;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务监控采集（S48 2.3-F6）：oshi + JMX + 虚拟线程调度器 MXBean。
 * <p>
 * CPU 占比需两次采样取差值，间隔 300ms（页面每次刷新阻塞 0.3s，可接受）。
 */
@Service
public class ServerInfoService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    /** CPU 两次采样间隔（毫秒） */
    private static final long CPU_SAMPLE_INTERVAL_MS = 300;

    public ServerInfoVO collect() {
        ServerInfoVO vo = new ServerInfoVO();
        SystemInfo si = new SystemInfo();
        vo.setCpu(buildCpu(si));
        vo.setMem(buildMem(si));
        vo.setJvm(buildJvm());
        vo.setVirtualThreads(buildVirtualThreads());
        vo.setSys(buildSys());
        vo.setSysFiles(buildSysFiles(si));
        return vo;
    }

    private ServerInfoVO.Cpu buildCpu(SystemInfo si) {
        CentralProcessor processor = si.getHardware().getProcessor();
        long[] prev = processor.getSystemCpuLoadTicks();
        try {
            Thread.sleep(CPU_SAMPLE_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long[] cur = processor.getSystemCpuLoadTicks();

        ServerInfoVO.Cpu cpu = new ServerInfoVO.Cpu();
        cpu.setPhysicalNum(processor.getPhysicalProcessorCount());
        cpu.setCpuNum(processor.getLogicalProcessorCount());

        long user = cur[CentralProcessor.TickType.USER.getIndex()] - prev[CentralProcessor.TickType.USER.getIndex()];
        long nice = cur[CentralProcessor.TickType.NICE.getIndex()] - prev[CentralProcessor.TickType.NICE.getIndex()];
        long sys = cur[CentralProcessor.TickType.SYSTEM.getIndex()] - prev[CentralProcessor.TickType.SYSTEM.getIndex()];
        long idle = cur[CentralProcessor.TickType.IDLE.getIndex()] - prev[CentralProcessor.TickType.IDLE.getIndex()];
        long ioWait = cur[CentralProcessor.TickType.IOWAIT.getIndex()] - prev[CentralProcessor.TickType.IOWAIT.getIndex()];
        long irq = cur[CentralProcessor.TickType.IRQ.getIndex()] - prev[CentralProcessor.TickType.IRQ.getIndex()];
        long softIrq = cur[CentralProcessor.TickType.SOFTIRQ.getIndex()] - prev[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        long steal = cur[CentralProcessor.TickType.STEAL.getIndex()] - prev[CentralProcessor.TickType.STEAL.getIndex()];
        long total = user + nice + sys + idle + ioWait + irq + softIrq + steal;
        if (total > 0) {
            cpu.setUsed(round2((user + nice) * 100.0 / total));
            cpu.setSys(round2(sys * 100.0 / total));
            cpu.setIdle(round2(idle * 100.0 / total));
            cpu.setWait(round2(ioWait * 100.0 / total));
        }
        return cpu;
    }

    private ServerInfoVO.Mem buildMem(SystemInfo si) {
        GlobalMemory memory = si.getHardware().getMemory();
        ServerInfoVO.Mem mem = new ServerInfoVO.Mem();
        mem.setTotal(memory.getTotal());
        mem.setFree(memory.getAvailable());
        mem.setUsed(memory.getTotal() - memory.getAvailable());
        mem.setUsage(memory.getTotal() == 0 ? 0
                : round2((memory.getTotal() - memory.getAvailable()) * 100.0 / memory.getTotal()));
        return mem;
    }

    private ServerInfoVO.Jvm buildJvm() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var threads = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        ServerInfoVO.Jvm jvm = new ServerInfoVO.Jvm();
        jvm.setName(runtime.getVmName());
        jvm.setVersion(System.getProperty("java.version"));
        jvm.setVendor(runtime.getVmVendor());
        jvm.setHome(System.getProperty("java.home"));
        jvm.setStartTime(FMT.format(Instant.ofEpochMilli(runtime.getStartTime())));
        jvm.setUptimeMillis(runtime.getUptime());
        jvm.setInputArgs(runtime.getInputArguments().stream().collect(Collectors.joining(" ")));
        jvm.setHeapInit(heap.getInit());
        jvm.setHeapUsed(heap.getUsed());
        jvm.setHeapCommitted(heap.getCommitted());
        jvm.setHeapMax(heap.getMax());
        jvm.setNonHeapUsed(nonHeap.getUsed());
        jvm.setThreadCount(threads.getThreadCount());
        jvm.setPeakThreadCount(threads.getPeakThreadCount());
        jvm.setDaemonThreadCount(threads.getDaemonThreadCount());
        return jvm;
    }

    /**
     * 虚拟线程调度器指标。JDK 21+ 提供 jdk.management.VirtualThreadSchedulerMXBean
     * （JMX ThreadMXBean 只统计平台线程，虚拟线程必须走这个专用 MXBean）；
     * 老 JDK 上 MXBean 不存在，返回 supported=false 由前端隐藏该卡片。
     */
    private ServerInfoVO.VirtualThreads buildVirtualThreads() {
        ServerInfoVO.VirtualThreads vt = new ServerInfoVO.VirtualThreads();
        var mxBean = ManagementFactory.getPlatformMXBean(jdk.management.VirtualThreadSchedulerMXBean.class);
        if (mxBean == null) {
            vt.setSupported(false);
            return vt;
        }
        vt.setSupported(true);
        vt.setParallelism(mxBean.getParallelism());
        vt.setPoolSize(mxBean.getPoolSize());
        vt.setMounted(mxBean.getMountedVirtualThreadCount());
        vt.setQueued(mxBean.getQueuedVirtualThreadCount());
        return vt;
    }

    private ServerInfoVO.Sys buildSys() {
        ServerInfoVO.Sys sys = new ServerInfoVO.Sys();
        sys.setOsName(System.getProperty("os.name"));
        sys.setOsVersion(System.getProperty("os.version"));
        sys.setOsArch(System.getProperty("os.arch"));
        try {
            InetAddress addr = InetAddress.getLocalHost();
            sys.setHostName(addr.getHostName());
            sys.setIp(addr.getHostAddress());
        } catch (Exception e) {
            sys.setHostName("未知");
            sys.setIp("未知");
        }
        return sys;
    }

    private List<ServerInfoVO.SysFile> buildSysFiles(SystemInfo si) {
        List<ServerInfoVO.SysFile> files = new ArrayList<>();
        for (OSFileStore store : si.getOperatingSystem().getFileSystem().getFileStores()) {
            long total = store.getTotalSpace();
            if (total <= 0) {
                continue;
            }
            long free = store.getUsableSpace();
            ServerInfoVO.SysFile file = new ServerInfoVO.SysFile();
            file.setDirName(store.getMount());
            file.setSysTypeName(store.getType());
            file.setTypeName(store.getName());
            file.setTotal(total);
            file.setFree(free);
            file.setUsed(total - free);
            file.setUsage(round2((total - free) * 100.0 / total));
            files.add(file);
        }
        return files;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

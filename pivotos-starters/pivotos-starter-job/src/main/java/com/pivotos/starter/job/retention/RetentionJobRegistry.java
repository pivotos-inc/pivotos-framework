package com.pivotos.starter.job.retention;

import com.pivotos.starter.job.api.JobHandlerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 留存清理任务登记处：供本地调度兜底遍历。
 * <p>
 * <b>为什么不用既有的 {@code JobHandlerRegistry}</b>：那个注册表里还有
 * 「AI Key 健康度巡检」这类非清理任务。本地兜底的职责是「XXL-Job 不可达时代替它跑留存清理」，
 * 不该顺手把巡检任务也拉进本地调度（语义越界且执行时机不同）。故独立登记，职责单一，
 * 同时各任务仍照旧注册进 {@code JobHandlerRegistry} 供管理端手动触发——两者互不替代。
 *
 * @author PivotOS
 * @since 3.0.0
 */
public class RetentionJobRegistry {

    private record Entry(Runnable runnable, String displayName) {
    }

    private final Map<String, Entry> jobs = new ConcurrentHashMap<>();

    /**
     * 登记一个留存清理任务。
     *
     * @param handler     XXL-Job handler 名（与 @XxlJob value 一致）
     * @param displayName 中文显示名
     * @param runnable    执行体（已包裹记录与异常捕获）
     */
    public void register(String handler, String displayName, Runnable runnable) {
        jobs.put(handler, new Entry(runnable, displayName));
    }

    /** 按名查找。 */
    public Optional<Runnable> find(String handler) {
        Entry entry = jobs.get(handler);
        return entry == null ? Optional.empty() : Optional.of(entry.runnable());
    }

    /** 列出全部已登记任务（顺序不保证）。 */
    public List<JobHandlerInfo> list() {
        List<JobHandlerInfo> result = new ArrayList<>(jobs.size());
        jobs.forEach((handler, entry) -> result.add(new JobHandlerInfo(handler, entry.displayName())));
        return result;
    }

    /** 已登记任务数（测试与启动日志自检用）。 */
    public int size() {
        return jobs.size();
    }
}

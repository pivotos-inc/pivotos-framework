package com.pivotos.starter.job.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动触发注册表：@XxlJob handler 名 → 可执行体 + 显示名。
 * <p>
 * 各 Job 在 @PostConstruct 自注册，供管理端「手动触发」按名调用；
 * 与 XXL-Job 调度中心解耦（dev 无调度中心时也能验证任务逻辑）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
public class JobHandlerRegistry {

    private record Entry(Runnable runnable, String displayName) {
    }

    private final Map<String, Entry> handlers = new ConcurrentHashMap<>();

    /**
     * 注册可手动触发的任务（显示名默认取 handler 名）。
     *
     * @param handler  XXL-Job handler 名（与 @XxlJob value 一致）
     * @param runnable 执行体
     */
    public void register(String handler, Runnable runnable) {
        handlers.put(handler, new Entry(runnable, handler));
    }

    /**
     * 注册可手动触发的任务（带中文显示名）。
     *
     * @param handler     XXL-Job handler 名
     * @param displayName 显示名（供管理端下拉）
     * @param runnable   执行体
     */
    public void register(String handler, String displayName, Runnable runnable) {
        handlers.put(handler, new Entry(runnable, displayName));
    }

    /**
     * 按名查找任务。
     */
    public Optional<Runnable> find(String handler) {
        var entry = handlers.get(handler);
        return entry != null ? Optional.of(entry.runnable()) : Optional.empty();
    }

    /**
     * 列出所有已注册的 Handler 元数据（供管理端下拉选择）。
     */
    public List<JobHandlerInfo> list() {
        List<JobHandlerInfo> result = new ArrayList<>(handlers.size());
        handlers.forEach((handler, entry) ->
                result.add(new JobHandlerInfo(handler, entry.displayName())));
        return result;
    }
}

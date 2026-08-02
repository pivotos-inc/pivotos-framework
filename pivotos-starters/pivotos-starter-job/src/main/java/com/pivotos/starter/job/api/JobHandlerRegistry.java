package com.pivotos.starter.job.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动触发注册表：@XxlJob handler 名 → 可执行体。
 * <p>
 * 各 Job 在 @PostConstruct 自注册，供管理端「手动触发」按名调用；
 * 与 XXL-Job 调度中心解耦（dev 无调度中心时也能验证任务逻辑）。
 *
 * @author PivotOS
 * @since 2.2.0
 */
public class JobHandlerRegistry {

    private final Map<String, Runnable> handlers = new ConcurrentHashMap<>();

    /**
     * 注册可手动触发的任务。
     *
     * @param handler  XXL-Job handler 名（与 @XxlJob value 一致）
     * @param runnable 执行体
     */
    public void register(String handler, Runnable runnable) {
        handlers.put(handler, runnable);
    }

    /**
     * 按名查找任务。
     */
    public Optional<Runnable> find(String handler) {
        return Optional.ofNullable(handlers.get(handler));
    }
}

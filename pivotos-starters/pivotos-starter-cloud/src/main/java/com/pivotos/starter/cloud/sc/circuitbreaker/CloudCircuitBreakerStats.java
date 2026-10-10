package com.pivotos.starter.cloud.sc.circuitbreaker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通道熔断的可观测计数器。
 *
 * <p>为什么自己数而不是只靠日志：日志能证明「发生过」，但断言不了「没发生」。
 * {@code notPermitted} 这类计数是「熔断真的拦住了请求」最硬的证据——
 * 只有它大于 0，才能说明请求确实没打到下游（而不是下游恰好又失败了一次）。
 */
public class CloudCircuitBreakerStats {

    private final ConcurrentHashMap<String, Counters> byName = new ConcurrentHashMap<>();

    public void recordCall(String name) {
        counters(name).calls.incrementAndGet();
    }

    public void recordSuccess(String name) {
        counters(name).successes.incrementAndGet();
    }

    public void recordFailure(String name) {
        counters(name).failures.incrementAndGet();
    }

    public void recordNotPermitted(String name) {
        counters(name).notPermitted.incrementAndGet();
    }

    public void recordFallback(String name) {
        counters(name).fallbacks.incrementAndGet();
    }

    public void recordFallbackFailure(String name) {
        counters(name).fallbackFailures.incrementAndGet();
    }

    /** 单个熔断实例的计数快照（值拷贝，调用后不再随运行变化） */
    public Snapshot snapshot(String name) {
        Counters counters = byName.get(name);
        return counters == null ? Snapshot.empty() : counters.snapshot();
    }

    /** 全量快照（按名称字典序） */
    public Map<String, Snapshot> snapshotAll() {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        byName.keySet().stream().sorted().forEach(k -> result.put(k, byName.get(k).snapshot()));
        return Collections.unmodifiableMap(result);
    }

    /** 全局求和（跨熔断实例） */
    public Snapshot total() {
        Snapshot total = Snapshot.empty();
        for (Counters counters : byName.values()) {
            total = total.plus(counters.snapshot());
        }
        return total;
    }

    public void reset() {
        byName.clear();
    }

    private Counters counters(String name) {
        return byName.computeIfAbsent(name, k -> new Counters());
    }

    private static final class Counters {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong successes = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong notPermitted = new AtomicLong();
        private final AtomicLong fallbacks = new AtomicLong();
        private final AtomicLong fallbackFailures = new AtomicLong();

        Snapshot snapshot() {
            return new Snapshot(calls.get(), successes.get(), failures.get(),
                notPermitted.get(), fallbacks.get(), fallbackFailures.get());
        }
    }

    /** 计数快照：calls / successes / failures / notPermitted / fallbacks / fallbackFailures */
    public record Snapshot(long calls,
                           long successes,
                           long failures,
                           long notPermitted,
                           long fallbacks,
                           long fallbackFailures) {

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0);
        }

        public Snapshot plus(Snapshot other) {
            return new Snapshot(calls + other.calls, successes + other.successes, failures + other.failures,
                notPermitted + other.notPermitted, fallbacks + other.fallbacks,
                fallbackFailures + other.fallbackFailures);
        }
    }
}

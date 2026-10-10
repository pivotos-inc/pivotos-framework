package com.pivotos.starter.cloud.sc.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * cloud 通道熔断的门面：{@code pivotos.cloud.sc.circuit-breaker.*} → Resilience4j 实例。
 *
 * <p><b>为什么直接用 Resilience4j 原生 API 而不是 Spring Cloud 的 {@code CircuitBreakerFactory} 抽象</b>：
 * <ol>
 *   <li>SC 抽象的 {@code run(supplier, fallback)} 要求 fallback 是<b>返回值</b>的函数，
 *       天然诱导「返回个 null 算了」；而本项目的降级口径是「要么显式抛、要么落到真实替代实现」，
 *       原生 API（OPEN 时抛 {@code CallNotPermittedException}）与之一致；</li>
 *   <li>走 SC 抽象要先有 {@code Resilience4JAutoConfiguration} 装配出的工厂，而它依赖
 *       resilience4j-spring-boot3 的 registry Bean 在 Boot 4 下是否被加载——
 *       那是一条我们控制不了、且失败形态是「启动期缺 Bean」的链路；</li>
 *   <li>配置口径统一在 {@code pivotos.*}，不要求用户再写一套 {@code resilience4j.*}。</li>
 * </ol>
 *
 * <p>可观测性三件事：状态转换与拒绝打 WARN（这是排障时最需要的两条），
 * 单次失败只打 DEBUG（下游持续失败时逐条 WARN 会把自己刷成噪音，异常本就会由调用方处理）。
 */
public final class CloudCircuitBreakerSupport {

    private static final Logger log = LoggerFactory.getLogger(CloudCircuitBreakerSupport.class);

    private final ScCircuitBreakerProperties properties;
    private final CircuitBreakerRegistry registry;
    private final CloudCircuitBreakerStats stats;
    private final CircuitBreakerConfig config;
    private final Set<String> instrumented = ConcurrentHashMap.newKeySet();

    public CloudCircuitBreakerSupport(ScCircuitBreakerProperties properties,
                                      CircuitBreakerRegistry registry,
                                      CloudCircuitBreakerStats stats) {
        this.properties = properties == null ? new ScCircuitBreakerProperties() : properties;
        this.registry = registry == null ? CircuitBreakerRegistry.ofDefaults() : registry;
        this.stats = stats == null ? new CloudCircuitBreakerStats() : stats;
        this.config = buildConfig(this.properties);
    }

    /** 熔断实例名：{@code <prefix>.<Iface>[#method]}（粒度由 granularity 决定） */
    public String nameOf(Class<?> iface, Method method) {
        String prefix = properties.getNamePrefix() == null || properties.getNamePrefix().isBlank()
            ? "pivotosCloud" : properties.getNamePrefix().trim();
        String base = prefix + "." + iface.getSimpleName();
        return properties.perMethod() ? base + "#" + method.getName() : base;
    }

    /** 取（或建）熔断实例；首次取到时挂上事件日志 */
    public CircuitBreaker circuitBreaker(String name) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(name, config);
        if (instrumented.add(name)) {
            instrument(circuitBreaker);
        }
        return circuitBreaker;
    }

    public CircuitBreakerConfig config() {
        return config;
    }

    public ScCircuitBreakerProperties properties() {
        return properties;
    }

    public CloudCircuitBreakerStats stats() {
        return stats;
    }

    /** 生效配置的可读快照（启动日志与排障用） */
    public Map<String, Object> describe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("granularity", properties.perMethod() ? "method" : "interface");
        map.put("fallbackMode", properties.fallbackMode().name());
        map.put("slidingWindowSize", properties.getSlidingWindowSize());
        map.put("minimumNumberOfCalls", properties.getMinimumNumberOfCalls());
        map.put("failureRateThreshold", properties.getFailureRateThreshold());
        map.put("waitDurationInOpenState", String.valueOf(properties.getWaitDurationInOpenState()));
        map.put("permittedNumberOfCallsInHalfOpenState", properties.getPermittedNumberOfCallsInHalfOpenState());
        map.put("automaticTransitionFromOpenToHalfOpenEnabled",
            properties.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        map.put("ignoreExceptions", properties.getIgnoreExceptions());
        map.put("recordExceptions", properties.getRecordExceptions());
        return map;
    }

    private void instrument(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> log.warn("[CLOUD][cloud][CB] {} 状态转换：{} -> {}（failureRate={}%，buffered={}）",
                circuitBreaker.getName(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState(),
                circuitBreaker.getMetrics().getFailureRate(),
                circuitBreaker.getMetrics().getNumberOfBufferedCalls()))
            .onCallNotPermitted(event -> log.warn("[CLOUD][cloud][CB] {} 拒绝调用（当前状态 {}）",
                circuitBreaker.getName(), circuitBreaker.getState()))
            .onError(event -> log.debug("[CLOUD][cloud][CB] {} 记录一次失败：{}",
                circuitBreaker.getName(), event.getThrowable()));
    }

    private static CircuitBreakerConfig buildConfig(ScCircuitBreakerProperties properties) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(Math.max(1, properties.getSlidingWindowSize()))
            .minimumNumberOfCalls(Math.max(1, properties.getMinimumNumberOfCalls()))
            .failureRateThreshold(properties.getFailureRateThreshold())
            .waitDurationInOpenState(positiveDuration(properties.getWaitDurationInOpenState()))
            .permittedNumberOfCallsInHalfOpenState(Math.max(1, properties.getPermittedNumberOfCallsInHalfOpenState()))
            .automaticTransitionFromOpenToHalfOpenEnabled(properties.isAutomaticTransitionFromOpenToHalfOpenEnabled());

        // 空 = 维持 Resilience4j 默认（记录所有 Throwable）；显式配置才覆盖，
        // 因为「记录一部分异常」比「记录全部」更容易配错，配错的形态是熔断永不打开。
        List<Class<? extends Throwable>> record = load(properties.getRecordExceptions());
        if (!record.isEmpty()) {
            builder.recordExceptions(toArray(record));
        }
        List<Class<? extends Throwable>> ignore = load(properties.getIgnoreExceptions());
        if (!ignore.isEmpty()) {
            builder.ignoreExceptions(toArray(ignore));
        }
        return builder.build();
    }

    /**
     * {@code List<Class<? extends Throwable>>} → 变长参数需要的数组形态。
     * Java 不允许创建「带 extends 通配符」的泛型数组（只允许 {@code new Class<?>[0]}），
     * 因此这里建无界数组再转型；元素类型已在上一步 {@code asSubclass} 处校验过。
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable>[] toArray(List<Class<? extends Throwable>> types) {
        return types.toArray((Class<? extends Throwable>[]) new Class<?>[0]);
    }

    private static java.time.Duration positiveDuration(java.time.Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return java.time.Duration.ofSeconds(10);
        }
        return duration;
    }

    /** 异常类名 → Class；加载不到的跳过并 WARN（配错类名不该让应用起不来） */
    private static List<Class<? extends Throwable>> load(List<String> classNames) {
        List<Class<? extends Throwable>> result = new ArrayList<>();
        if (classNames == null) {
            return result;
        }
        for (String className : classNames) {
            if (className == null || className.isBlank()) {
                continue;
            }
            try {
                Class<?> type = ClassUtils.forName(className.trim(), CloudCircuitBreakerSupport.class.getClassLoader());
                if (Throwable.class.isAssignableFrom(type)) {
                    result.add(type.asSubclass(Throwable.class));
                } else {
                    log.warn("[CLOUD][cloud][CB] 忽略非 Throwable 的异常配置：{}", className);
                }
            } catch (Exception e) {
                log.warn("[CLOUD][cloud][CB] 异常类无法加载，忽略该配置：{}", className);
            }
        }
        return result;
    }
}

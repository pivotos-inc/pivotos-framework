package com.pivotos.starter.cloud.sc.circuitbreaker;

import com.pivotos.starter.cloud.sc.fixture.EchoFacade;
import com.pivotos.starter.cloud.sc.fixture.RecordingEchoFacade;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断包装的核心行为测试（真实 Resilience4j 状态机，零 Spring 容器）。
 *
 * <p>这组用例的取证目标是「引入 ≠ 生效」的另一半：<b>生效要能被看见</b>。
 * 关键断言不是「抛异常」，而是「熔断打开后下游计数器不再增长」——
 * 前者可能被「下游恰好又失败了」蒙混过关，后者不会。
 */
class CircuitBreakerFacadeProxyTest {

    @Test
    void 熔断打开后请求不再打到下游() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        CloudCircuitBreakerSupport support = support(fastTrip());
        EchoFacade proxy = wrap(downstream, support, () -> null);

        // 窗口 2 / 最小调用数 2 / 阈值 50%：两次全失败即打开
        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);
        assertThat(downstream.calls()).as("熔断前两次调用确实打到了下游").isEqualTo(2);

        CircuitBreaker circuitBreaker = support.circuitBreaker(support.nameOf(EchoFacade.class, echoMethod()));
        assertThat(circuitBreaker.getState()).as("失败率 100% 应打开熔断").isEqualTo(CircuitBreaker.State.OPEN);

        int before = downstream.calls();
        assertThatThrownBy(() -> proxy.echo("c"))
            .as("熔断打开后必须抛带状态的异常，绝不静默返回 null")
            .isInstanceOf(CloudCircuitBreakerOpenException.class)
            .hasMessageContaining("pivotosCloud.EchoFacade");

        assertThat(downstream.calls()).as("熔断打开后请求根本没发出去，下游计数器不允许增长").isEqualTo(before);
        assertThat(support.stats().snapshot(circuitBreaker.getName()).notPermitted()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls())
            .as("Resilience4j 自己的计数也要对得上（双重证据）").isGreaterThanOrEqualTo(1);
    }

    @Test
    void 单次调用失败原样抛出原始异常且只计数不包装() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        CloudCircuitBreakerSupport support = support(fastTrip());
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThatThrownBy(() -> proxy.echo("x"))
            .as("单次失败必须原样抛：包装一层会让调用方既有的 catch 逻辑失效")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("下游不可用：x");

        assertThat(support.stats().snapshot(support.nameOf(EchoFacade.class, echoMethod())).failures()).isEqualTo(1);
        assertThat(support.stats().snapshot(support.nameOf(EchoFacade.class, echoMethod())).notPermitted()).isZero();
    }

    @Test
    void 等待窗口过后半开探测成功即恢复() throws Exception {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        CloudCircuitBreakerSupport support = support(fastTrip());
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);
        CircuitBreaker circuitBreaker = support.circuitBreaker(support.nameOf(EchoFacade.class, echoMethod()));
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(400);
        downstream.healTo("ok");

        assertThat(proxy.echo("c")).as("OPEN 时长过后应放行一次探测并成功").isEqualTo("ok:c");
        assertThat(circuitBreaker.getState()).as("半开探测成功应回到 CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void local降级回落到本地实现并计数() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        RecordingEchoFacade local = new RecordingEchoFacade();
        local.healTo("local");
        CloudCircuitBreakerSupport support = support(fallback(FallbackMode.LOCAL));
        EchoFacade proxy = wrap(downstream, support, () -> local);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);

        assertThat(proxy.echo("c")).as("降级口径 local：应回落到进程内实现，而不是返回 null")
            .isEqualTo("local:c");
        assertThat(local.calls()).isEqualTo(1);
        assertThat(support.stats().snapshot(support.nameOf(EchoFacade.class, echoMethod())).fallbacks()).isEqualTo(1);
    }

    @Test
    void local降级在本地实现缺席时照抛异常() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        CloudCircuitBreakerSupport support = support(fallback(FallbackMode.LOCAL));
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> proxy.echo("c"))
            .as("本地实现缺席时绝不返回 null，必须抛出去")
            .isInstanceOf(CloudCircuitBreakerOpenException.class);
        assertThat(support.stats().snapshot(support.nameOf(EchoFacade.class, echoMethod())).fallbacks()).isZero();
    }

    @Test
    void 本地实现自身抛异常时不吞() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        RecordingEchoFacade local = new RecordingEchoFacade();  // 保持 failing
        CloudCircuitBreakerSupport support = support(fallback(FallbackMode.LOCAL));
        EchoFacade proxy = wrap(downstream, support, () -> local);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> proxy.echo("c"))
            .as("降级实现自己失败了也要如实抛出")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("下游不可用：c");
        assertThat(support.stats().snapshot(support.nameOf(EchoFacade.class, echoMethod())).fallbackFailures())
            .isEqualTo(1);
    }

    @Test
    void object方法不进熔断() {
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        CloudCircuitBreakerSupport support = support(fastTrip());
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThat(proxy.toString()).isNotNull();
        assertThat(proxy.hashCode()).isNotZero();
        assertThat(support.stats().total().calls())
            .as("toString/hashCode 不是远程调用，计入窗口只会污染失败率").isZero();
    }

    @Test
    void 方法粒度命名按方法分桶() throws Exception {
        ScCircuitBreakerProperties properties = fastTrip();
        properties.setGranularity("method");
        CloudCircuitBreakerSupport support = support(properties);
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);
        // ping 是独立实例：echo 已熔断不该把它连坐，ping 自己第一次调用仍会打到下游
        assertThatThrownBy(proxy::ping).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("c")).isInstanceOf(CloudCircuitBreakerOpenException.class);

        Map<String, CloudCircuitBreakerStats.Snapshot> all = support.stats().snapshotAll();
        assertThat(all).as("method 粒度下 echo 与 ping 各一个熔断实例")
            .containsKey("pivotosCloud.EchoFacade#echo")
            .containsKey("pivotosCloud.EchoFacade#ping");
        assertThat(all.get("pivotosCloud.EchoFacade#echo").notPermitted()).isEqualTo(1);
        assertThat(all.get("pivotosCloud.EchoFacade#ping").notPermitted()).as("ping 未被连坐").isZero();
        assertThat(all.get("pivotosCloud.EchoFacade#ping").calls()).isEqualTo(1);
    }

    @Test
    void 接口粒度下不同方法共用一个熔断实例() {
        CloudCircuitBreakerSupport support = support(fastTrip());
        RecordingEchoFacade downstream = new RecordingEchoFacade();
        EchoFacade proxy = wrap(downstream, support, () -> null);

        assertThatThrownBy(() -> proxy.echo("a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proxy.echo("b")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(proxy::ping).isInstanceOf(CloudCircuitBreakerOpenException.class);

        assertThat(support.stats().snapshotAll())
            .as("默认 interface 粒度：整个 Facade 一个实例")
            .containsOnlyKeys("pivotosCloud.EchoFacade");
    }

    @Test
    void 生效配置可被读回() {
        ScCircuitBreakerProperties properties = fallback(FallbackMode.LOCAL);
        properties.setFailureRateThreshold(77);
        properties.setSlidingWindowSize(9);
        properties.setMinimumNumberOfCalls(3);
        properties.setIgnoreExceptions(List.of("java.lang.IllegalStateException"));

        CloudCircuitBreakerSupport support = support(properties);

        assertThat(support.config().getFailureRateThreshold()).isEqualTo(77f);
        assertThat(support.config().getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(support.config().getSlidingWindowSize()).isEqualTo(9);
        assertThat(support.describe())
            .containsEntry("fallbackMode", "LOCAL")
            .containsEntry("granularity", "interface")
            .containsEntry("failureRateThreshold", 77);
    }

    // ===== helper =====

    private static EchoFacade wrap(Object target,
                                   CloudCircuitBreakerSupport support,
                                   Supplier<Object> localFallback) {
        return (EchoFacade) CircuitBreakerFacadeProxy.wrap(EchoFacade.class, target, support, localFallback);
    }

    private static Method echoMethod() {
        try {
            return EchoFacade.class.getMethod("echo", String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ScCircuitBreakerProperties fastTrip() {
        ScCircuitBreakerProperties properties = new ScCircuitBreakerProperties();
        properties.setEnabled(true);
        properties.setSlidingWindowSize(2);
        properties.setMinimumNumberOfCalls(2);
        properties.setFailureRateThreshold(50);
        properties.setWaitDurationInOpenState(Duration.ofMillis(250));
        properties.setPermittedNumberOfCallsInHalfOpenState(1);
        return properties;
    }

    private static ScCircuitBreakerProperties fallback(FallbackMode mode) {
        ScCircuitBreakerProperties properties = fastTrip();
        properties.setFallbackMode(mode.name());
        return properties;
    }

    private static CloudCircuitBreakerSupport support(ScCircuitBreakerProperties properties) {
        return new CloudCircuitBreakerSupport(properties, CircuitBreakerRegistry.ofDefaults(),
            new CloudCircuitBreakerStats());
    }
}

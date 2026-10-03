package com.pivotos.starter.cloud.alibaba.sentinel;

import com.pivotos.starter.cloud.alibaba.config.AlibabaCloudProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sentinel 限流**真机**测试：不需要控制台、不需要任何外部服务。
 *
 * <p>为什么这条测试重要：Sentinel 的「装上了」和「真的会限流」是两件事。
 * 只有真跑一次 SphU.entry 并观察 BlockException，才能说熔断/限流通道是真的通了
 * （这也是本项目一贯的口径：NoSuchMethodError 类缺陷只有真跑才会暴露）。
 */
class SentinelGuardTest {

    @Test
    void blocks_second_call_within_qps_limit() {
        AlibabaCloudProperties properties = new AlibabaCloudProperties();
        properties.setSentinelEnabled(true);
        properties.setRules(Map.of("sentinel-guard-test-a", 1));
        SentinelGuard guard = new SentinelGuard(properties);

        assertThat(guard.protect("sentinel-guard-test-a", () -> "first"))
            .as("第一次调用必须放行").isEqualTo("first");

        assertThatThrownBy(() -> guard.protect("sentinel-guard-test-a", () -> "second"))
            .as("同一秒内第二次调用必须被限流")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("限流");
    }

    @Test
    void passes_through_when_disabled() {
        AlibabaCloudProperties properties = new AlibabaCloudProperties();
        properties.setSentinelEnabled(false);
        properties.setRules(Map.of("sentinel-guard-test-b", 1));
        SentinelGuard guard = new SentinelGuard(properties);

        for (int i = 0; i < 5; i++) {
            assertThat(guard.protect("sentinel-guard-test-b", () -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void resource_name_is_prefixed() {
        AlibabaCloudProperties properties = new AlibabaCloudProperties();
        properties.setResourcePrefix("pivotos");
        SentinelGuard guard = new SentinelGuard(properties);
        assertThat(guard.resource("demo")).isEqualTo("pivotos:demo");

        properties.setResourcePrefix("");
        assertThat(new SentinelGuard(properties).resource("demo")).isEqualTo("demo");
    }

    @Test
    void no_rules_means_no_active_limiting() {
        AlibabaCloudProperties properties = new AlibabaCloudProperties();
        properties.setSentinelEnabled(true);
        SentinelGuard guard = new SentinelGuard(properties);
        for (int i = 0; i < 3; i++) {
            final int expected = i;
            assertThat(guard.protect("sentinel-guard-test-c", () -> expected)).isEqualTo(expected);
        }
    }
}

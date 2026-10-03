package com.pivotos.starter.cloud.local.config;

import com.pivotos.starter.cloud.api.config.CloudProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * local 通道装配边界测试：重点验「配错要响」与「引入 ≠ 生效」两条铁律。
 */
class LocalCloudAutoConfigurationTest {

    @Test
    void server_enabled_without_internal_token_fails_fast() {
        CloudProperties cloudProps = new CloudProperties();
        cloudProps.getContext().setInternalToken("");

        assertThatThrownBy(() -> new LocalCloudAutoConfiguration().facadeRpcServlet(cloudProps))
            .as("开了 RPC 端点却不配内部凭证 = 公开反射后门，必须 fail-fast")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("internal-token");
    }

    @Test
    void proxied_parsing_tolerates_blanks() {
        LocalCloudProperties properties = new LocalCloudProperties();
        assertThat(properties.proxiedInterfaces()).as("默认必须为空：不配就不替换任何 Bean").isEmpty();

        properties.setProxied(" com.pivotos.A , ,com.pivotos.B ");
        assertThat(properties.proxiedInterfaces()).containsExactly("com.pivotos.A", "com.pivotos.B");
    }

    @Test
    void resolved_instances_skips_invalid_entries() {
        LocalCloudProperties properties = new LocalCloudProperties();
        properties.setInstances(Map.of(
            "ok", "127.0.0.1:8080",
            "bad-port", "127.0.0.1:not-a-port",
            "no-colon", "127.0.0.1"));

        assertThat(properties.resolvedInstances())
            .as("一个配错的地址不该拖垮整张实例表")
            .containsOnlyKeys("ok");
    }
}

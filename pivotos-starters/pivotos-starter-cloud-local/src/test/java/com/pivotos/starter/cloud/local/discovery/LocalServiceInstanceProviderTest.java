package com.pivotos.starter.cloud.local.discovery;

import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.local.config.LocalCloudProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServiceInstanceProviderTest {

    @Test
    void resolves_static_instance_table() {
        LocalCloudProperties properties = new LocalCloudProperties();
        properties.setInstances(Map.of("pivotos-admin-server", "10.0.0.9:8080"));
        LocalServiceInstanceProvider provider = new LocalServiceInstanceProvider(properties);

        assertThat(provider.id()).isEqualTo("local");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.list("pivotos-admin-server"))
            .singleElement()
            .satisfies(instance -> {
                assertThat(instance.getHost()).isEqualTo("10.0.0.9");
                assertThat(instance.getPort()).isEqualTo(8080);
                assertThat(instance.baseUrl()).isEqualTo("http://10.0.0.9:8080");
            });
    }

    @Test
    void returns_empty_list_for_unknown_service_or_broken_address() {
        LocalCloudProperties properties = new LocalCloudProperties();
        properties.setInstances(Map.of("broken", "no-port-here"));
        LocalServiceInstanceProvider provider = new LocalServiceInstanceProvider(properties);

        assertThat(provider.list("unknown")).as("未登记的服务必须返回空列表，不抛异常").isEmpty();
        assertThat(properties.resolvedInstances()).as("地址格式非法时不解析出实例").isEmpty();
        assertThat(provider.isAvailable()).as("地址格式全错时该通道视为不可用").isFalse();
    }

    @Test
    void empty_table_means_unavailable() {
        LocalCloudProperties properties = new LocalCloudProperties();
        ServiceInstanceProvider provider = new LocalServiceInstanceProvider(properties);
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.list("anything")).isEmpty();
    }
}

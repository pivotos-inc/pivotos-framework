package com.pivotos.starter.cloud.sc.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScServiceInstanceProviderTest {

    @Test
    void maps_spring_cloud_instances() {
        ScServiceInstanceProvider provider = new ScServiceInstanceProvider(new StubDiscoveryClient());
        assertThat(provider.id()).isEqualTo("cloud");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.list("pivotos-admin-server"))
            .singleElement()
            .satisfies(instance -> {
                assertThat(instance.getHost()).isEqualTo("10.0.0.9");
                assertThat(instance.getPort()).isEqualTo(8080);
                assertThat(instance.getMetadata()).containsEntry("zone", "a");
            });
    }

    @Test
    void empty_when_discovery_client_absent() {
        ScServiceInstanceProvider provider = new ScServiceInstanceProvider(null);
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.list("pivotos-admin-server")).as("注册中心缺席必须返回空列表，不抛异常").isEmpty();
    }

    /** 最小 DiscoveryClient 替身（不引入注册中心也能验映射逻辑） */
    private static class StubDiscoveryClient implements DiscoveryClient {

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public List<org.springframework.cloud.client.ServiceInstance> getInstances(String serviceId) {
            return List.of(new DefaultServiceInstance("i-1", serviceId, "10.0.0.9", 8080, false, Map.of("zone", "a")));
        }

        @Override
        public List<String> getServices() {
            return List.of("pivotos-admin-server");
        }
    }
}

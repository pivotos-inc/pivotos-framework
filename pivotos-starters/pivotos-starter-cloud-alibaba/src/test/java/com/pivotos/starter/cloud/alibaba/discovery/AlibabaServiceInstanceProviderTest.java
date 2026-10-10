package com.pivotos.starter.cloud.alibaba.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlibabaServiceInstanceProviderTest {

    @Test
    void maps_nacos_instances() {
        AlibabaServiceInstanceProvider provider = new AlibabaServiceInstanceProvider(new StubDiscoveryClient());
        assertThat(provider.id()).isEqualTo("alibaba");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.first("pivotos-admin-server").getHost()).isEqualTo("10.0.0.9");
    }

    @Test
    void unavailable_without_discovery_client() {
        AlibabaServiceInstanceProvider provider = new AlibabaServiceInstanceProvider(null);
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.list("pivotos-admin-server")).isEmpty();
        assertThat(provider.first("pivotos-admin-server")).isNull();
    }

    @Test
    void degrades_to_empty_list_when_registry_unreachable() {
        AlibabaServiceInstanceProvider provider = new AlibabaServiceInstanceProvider(new FailingDiscoveryClient());
        assertThat(provider.list("pivotos-admin-server"))
            .as("注册中心不可达是部署常态，必须降级为空列表而不是抛异常")
            .isEmpty();
    }

    private static class StubDiscoveryClient implements DiscoveryClient {

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public List<org.springframework.cloud.client.ServiceInstance> getInstances(String serviceId) {
            return List.of(new DefaultServiceInstance("i-1", serviceId, "10.0.0.9", 8080, false));
        }

        @Override
        public List<String> getServices() {
            return List.of("pivotos-admin-server");
        }
    }

    private static class FailingDiscoveryClient implements DiscoveryClient {

        @Override
        public String description() {
            return "failing";
        }

        @Override
        public List<org.springframework.cloud.client.ServiceInstance> getInstances(String serviceId) {
            throw new IllegalStateException("nacos unreachable");
        }

        @Override
        public List<String> getServices() {
            throw new IllegalStateException("nacos unreachable");
        }
    }
}

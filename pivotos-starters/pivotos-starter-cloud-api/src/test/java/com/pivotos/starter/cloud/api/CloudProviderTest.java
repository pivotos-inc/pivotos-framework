package com.pivotos.starter.cloud.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudProviderTest {

    @Test
    void parses_loosely_and_falls_back_to_local() {
        assertThat(CloudProvider.of("local")).isEqualTo(CloudProvider.LOCAL);
        assertThat(CloudProvider.of("CLOUD")).isEqualTo(CloudProvider.CLOUD);
        assertThat(CloudProvider.of(" Alibaba ")).isEqualTo(CloudProvider.ALIBABA);
        assertThat(CloudProvider.of(null)).as("未配必须回落单体默认通道").isEqualTo(CloudProvider.LOCAL);
        assertThat(CloudProvider.of("")).isEqualTo(CloudProvider.LOCAL);
        assertThat(CloudProvider.of("nacos")).as("未知值回落 local，而不是抛异常让应用起不来").isEqualTo(CloudProvider.LOCAL);
    }
}

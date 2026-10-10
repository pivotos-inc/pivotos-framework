package com.pivotos.starter.cloud.api.discovery;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 服务实例：服务发现的返回值，刻意做成通道无关的 POJO。
 *
 * <p>为什么不用 Spring Cloud 的 {@code org.springframework.cloud.client.ServiceInstance}：
 * 那就等于把 cloud-api 绑死在 Spring Cloud 上，local 通道（零三方依赖）被迫带上
 * spring-cloud-commons —— 与「local 通道零三方依赖」的定位直接冲突。
 */
public class ServiceInstance implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String serviceId;
    private final String host;
    private final int port;
    private final boolean secure;
    private final Map<String, String> metadata;

    public ServiceInstance(String serviceId, String host, int port, boolean secure, Map<String, String> metadata) {
        this.serviceId = serviceId;
        this.host = host;
        this.port = port;
        this.secure = secure;
        this.metadata = metadata == null ? Collections.emptyMap() : new LinkedHashMap<>(metadata);
    }

    public static ServiceInstance of(String serviceId, String host, int port) {
        return new ServiceInstance(serviceId, host, port, false, null);
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isSecure() {
        return secure;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** 形如 {@code http://host:port} */
    public String baseUrl() {
        return (secure ? "https" : "http") + "://" + host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceInstance other)) {
            return false;
        }
        return port == other.port
            && secure == other.secure
            && Objects.equals(serviceId, other.serviceId)
            && Objects.equals(host, other.host)
            && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId, host, port, secure, metadata);
    }

    @Override
    public String toString() {
        return serviceId + "@" + baseUrl();
    }
}

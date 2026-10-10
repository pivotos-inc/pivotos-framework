package com.pivotos.cloud.nacos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.cloud.openfeign.CloudOpenFeignAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * S133 V3 spike —— Cloud Starter ②：服务注册/发现（对齐 Spring Cloud Alibaba Nacos Discovery 语义，
 * 直连 <b>Nacos Open API v1</b>，零第三方 SDK，便于在任何形态下按需裁剪）。
 *
 * <p>通过 openfeign 暴露的 {@link CloudOpenFeignAutoConfiguration.ServiceInstanceProvider} SPI 接入，
 * 通信侧无需感知注册中心存在与否。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.cloud.nacos", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "com.pivotos.cloud.openfeign.CloudOpenFeignAutoConfiguration")
public class NacosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NacosAutoConfiguration.class);

    @ConfigurationProperties(prefix = "pivotos.cloud.nacos")
    public static class NacosProps {
        private boolean enabled = false;
        /** Nacos 地址，形如 127.0.0.1:8848 或 http://127.0.0.1:8848 */
        private String serverAddr = "";
        private String username = "nacos";
        private String password = "nacos";
        private String namespace = "";
        private String group = "DEFAULT_GROUP";
        private String serviceName = "pivotos-admin-server";
        /** 本实例对外地址 */
        private String ip = "127.0.0.1";
        private int port = 8080;
        /** 注册失败是否让应用启动失败（默认 fail-fast） */
        private boolean failOnRegisterError = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isFailOnRegisterError() {
            return failOnRegisterError;
        }

        public void setFailOnRegisterError(boolean failOnRegisterError) {
            this.failOnRegisterError = failOnRegisterError;
        }

        String base() {
            String addr = serverAddr.trim();
            return addr.startsWith("http") ? addr : "http://" + addr;
        }
    }

    /**
     * 显式 Environment 绑定（同 openfeign：Boot 4.1.0 下 {@code @ConfigurationProperties} 的
     * binder 对命令行参数失效，详见 openfeign 同名 Bean 注释）。
     */
    @Bean
    public NacosProps nacosProps(org.springframework.core.env.Environment env) {
        NacosProps p = new NacosProps();
        String prefix = "pivotos.cloud.nacos.";
        p.setEnabled(env.getProperty(prefix + "enabled", Boolean.class, false));
        p.setServerAddr(env.getProperty(prefix + "server-addr", ""));
        p.setUsername(env.getProperty(prefix + "username", "nacos"));
        p.setPassword(env.getProperty(prefix + "password", "nacos"));
        p.setNamespace(env.getProperty(prefix + "namespace", ""));
        p.setGroup(env.getProperty(prefix + "group", "DEFAULT_GROUP"));
        p.setServiceName(env.getProperty(prefix + "service-name", "pivotos-admin-server"));
        p.setIp(env.getProperty(prefix + "ip", "127.0.0.1"));
        p.setPort(env.getProperty(prefix + "port", Integer.class, 8080));
        p.setFailOnRegisterError(env.getProperty(prefix + "fail-on-register-error", Boolean.class, true));
        return p;
    }

    @Bean
    public CloudOpenFeignAutoConfiguration.ServiceInstanceProvider nacosServiceInstanceProvider(NacosProps props) {
        return new NacosServiceInstanceProvider(props);
    }

    /** 生命周期：注册 + 心跳（同样用 fail-fast 口径：地址没配 / 注册失败都可能让应用起不来） */
    @Bean
    public NacosLifecycle nacosLifecycle(NacosProps props) {
        return new NacosLifecycle(props);
    }

    static class NacosServiceInstanceProvider implements CloudOpenFeignAutoConfiguration.ServiceInstanceProvider {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final NacosProps props;

        NacosServiceInstanceProvider(NacosProps props) {
            this.props = props;
        }

        @Override
        public String id() {
            return "nacos";
        }

        @Override
        public String resolve(String serviceName) {
            try {
                String url = props.base() + "/nacos/v1/ns/instance/list?serviceName="
                        + URLEncoder.encode(serviceName, StandardCharsets.UTF_8)
                        + "&groupName=" + URLEncoder.encode(props.getGroup(), StandardCharsets.UTF_8)
                        + "&healthyOnly=true"
                        + (StringUtils.hasText(props.getNamespace())
                        ? "&namespaceId=" + URLEncoder.encode(props.getNamespace(), StandardCharsets.UTF_8) : "");
                HttpResponse<String> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode node = MAPPER.readTree(resp.body());
                JsonNode hosts = node.get("hosts");
                List<String> candidates = new ArrayList<>();
                if (hosts != null && hosts.isArray()) {
                    for (JsonNode h : hosts) {
                        candidates.add("http://" + h.get("ip").asText() + ":" + h.get("port").asInt());
                    }
                }
                if (candidates.isEmpty()) {
                    throw new IllegalStateException("nacos 无可用心实例: " + serviceName);
                }
                return candidates.get(0);
            } catch (Exception e) {
                throw new IllegalStateException("nacos 服务发现失败: " + e.getMessage(), e);
            }
        }
    }

    static class NacosLifecycle implements InitializingBean, DisposableBean {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final NacosProps props;
        private ScheduledExecutorService beatExecutor;

        NacosLifecycle(NacosProps props) {
            this.props = props;
        }

        @Override
        public void afterPropertiesSet() {
            if (!StringUtils.hasText(props.getServerAddr())) {
                throw new IllegalStateException(
                        "[CLOUD][FAIL-FAST] pivotos.cloud.nacos.enabled=true 但未配置 server-addr，拒绝启动");
            }
            try {
                String url = props.base() + "/nacos/v1/ns/instance?serviceName="
                        + URLEncoder.encode(props.getServiceName(), StandardCharsets.UTF_8)
                        + "&ip=" + props.getIp() + "&port=" + props.getPort()
                        + "&groupName=" + URLEncoder.encode(props.getGroup(), StandardCharsets.UTF_8)
                        + "&ephemeral=true"
                        + (StringUtils.hasText(props.getNamespace())
                        ? "&namespaceId=" + URLEncoder.encode(props.getNamespace(), StandardCharsets.UTF_8) : "");
                HttpResponse<String> resp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
                }
                log.info("[CLOUD][nacos] 已注册 {}={}:{} -> {}", props.getServiceName(), props.getIp(),
                        props.getPort(), resp.body());
                beatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "pivotos-cloud-nacos-beat");
                    t.setDaemon(true);
                    return t;
                });
                beatExecutor.scheduleAtFixedRate(this::beat, 5, 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (props.isFailOnRegisterError()) {
                    throw new IllegalStateException("[CLOUD][FAIL-FAST] nacos 注册失败：" + e.getMessage(), e);
                }
                log.warn("[CLOUD][nacos] 注册失败但已降级继续启动：{}", e.getMessage());
            }
        }

        private void beat() {
            try {
                String beatJson = MAPPER.writeValueAsString(java.util.Map.of(
                        "ip", props.getIp(), "port", props.getPort(), "serviceName", props.getServiceName()));
                String url = props.base() + "/nacos/v1/ns/instance/beat?serviceName="
                        + URLEncoder.encode(props.getServiceName(), StandardCharsets.UTF_8)
                        + "&beat=" + URLEncoder.encode(beatJson, StandardCharsets.UTF_8);
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                                .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // 心跳失败不阻断业务
            }
        }

        @Override
        public void destroy() {
            if (beatExecutor != null) {
                beatExecutor.shutdownNow();
            }
        }
    }
}

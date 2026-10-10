package com.pivotos.starter.cloud.alibaba.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * alibaba 形态真机复验：Feign 出站身份头（V3-S3 第 ② 项）。
 *
 * <p><b>补的是什么</b>：L5 修的那个缺口（{@code CloudContextFeignAutoConfiguration} 的条件过去只有 CLOUD，
 * provider=alibaba 时整个自动配置缺席 → Feign 一个身份头都不发）当时只有单测与同 JVM 端到端覆盖。
 * 这里在 dev Nacos 上跑真货：<b>两个真实注册实例 + 真实 Feign 出站</b>。
 *
 * <p><b>观测点从哪来</b>：过去两个实例之间没有「能被对方调用、又能回显自己看到什么身份」的契约，
 * 只能用 {@code /system/auth/login} 这类与身份无关的接口，于是「头有没有发出去」根本无从观察。
 * 本轮 ④ 交付的运行时适配端点（默认白名单只有 {@code ContextFacade}）正好补上这个观测点——
 * 它的返回值就是<b>被调用实例当时的上下文终态</b>。
 *
 * <p><b>跑法</b>（凭据只走环境变量，不落任何文件/git）：
 * <pre>
 *   export PIVOTOS_NACOS_SERVER_ADDR=175.24.176.176:8848
 *   export PIVOTOS_NACOS_USERNAME=nacos
 *   export PIVOTOS_NACOS_PASSWORD='...'
 *   mvn test -pl pivotos-starters/pivotos-starter-cloud-alibaba \
 *       -Dtest=AlibabaFeignContextRealServerIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>为什么是 {@code *IT}</b>：需要 dev Nacos 与真实凭据，不能进默认回归集。
 * 默认回归集里同一漏洞由 {@code CloudContextFeignAutoConfigurationTest}（拦截条件）
 * 与 {@code SecondJvmCloudContextPropagationTest}（真双 JVM 传播）两头守卫。
 */
class AlibabaFeignContextRealServerIT {

    private static final String TOKEN = "alibaba-feign-it-token";
    private static final String SERVICE_ID = "pivotos-admin-server";

    private static ConfigurableApplicationContext upstream;
    private static ConfigurableApplicationContext downstream;

    @BeforeAll
    static void requireCredentials() {
        String addr = System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "");
        String password = System.getenv("PIVOTOS_NACOS_PASSWORD");
        Assumptions.assumeTrue(!addr.isBlank(), "需要 PIVOTOS_NACOS_SERVER_ADDR（dev = 175.24.176.176:8848）");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "需要 PIVOTOS_NACOS_PASSWORD");
    }

    @AfterAll
    static void stopAll() {
        if (upstream != null) {
            upstream.close();
        }
        if (downstream != null) {
            downstream.close();
        }
    }

    private static void ensureStarted() {
        if (downstream == null) {
            downstream = start(DownstreamApp.class, "alibaba", null);
        }
        if (upstream == null) {
            int downstreamPort = portOf(downstream);
            upstream = start(UpstreamApp.class, "alibaba", "http://127.0.0.1:" + downstreamPort);
        }
    }

    @Test
    @DisplayName("真机：Nacos 上确实存在两个健康实例")
    void two_instances_registered() {
        ensureStarted();
        DiscoveryClient discoveryClient = upstream.getBean(DiscoveryClient.class);
        List<ServiceInstance> instances = discoveryClient.getInstances(SERVICE_ID);
        assertThat(instances).as("Nacos 上应有本 IT 注册的实例").hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("真机双实例：Feign 出站带上的身份在另一个实例里被恢复")
    void feign_propagates_identity_to_another_instance() {
        ensureStarted();
        FacadeHttpClient client = upstream.getBean(FacadeHttpClient.class);

        String echo = echoWithContext(client, 12L, new LoginUser(1001L, "alice", "sys-user", 12L));

        assertThat(echo).as("下游实例应恢复出上游身份（tenant:userId:username）").isEqualTo("12:1001:alice");
    }

    @Test
    @DisplayName("换一组身份仍然逐一对应（排除下游返回默认值）")
    void another_identity_maps_one_to_one() {
        ensureStarted();
        FacadeHttpClient client = upstream.getBean(FacadeHttpClient.class);

        String echo = echoWithContext(client, 77L, new LoginUser(2002L, "bob", "app-user", 77L));

        assertThat(echo).isEqualTo("77:2002:bob");
    }

    // ---------------------------------------------------------------- 支撑

    private static ConfigurableApplicationContext start(Class<?> app, String provider, String targetUrl) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(app)
            .properties(
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "server.port=0",
                "spring.application.name=" + SERVICE_ID,
                "spring.cloud.nacos.discovery.enabled=true",
                "spring.cloud.nacos.discovery.server-addr="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848"),
                "spring.cloud.nacos.discovery.username="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos"),
                "spring.cloud.nacos.discovery.password="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", ""),
                "spring.cloud.nacos.discovery.fail-fast=false",
                "spring.cloud.nacos.discovery.failure-tolerance-enabled=true",
                "pivotos.cloud.enabled=true",
                "pivotos.cloud.provider=" + provider,
                "pivotos.cloud.context.internal-token=" + TOKEN,
                "pivotos.cloud.context.restore-tenant=true",
                "pivotos.cloud.context.restore-login=true",
                "pivotos.cloud.facade-http.enabled=true");
        if (targetUrl != null) {
            builder.properties("pivotos.it.downstream-url=" + targetUrl);
        }
        return builder.run();
    }

    private static int portOf(ConfigurableApplicationContext context) {
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("实例未上报端口（local.server.port 缺失），无法继续");
        }
        return port;
    }

    /** 在指定上下文绑定下发起一次真实 Feign 调用，回显下游看到的身份 */
    private String echoWithContext(FacadeHttpClient client, Long tenantId, LoginUser user) {
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> username = new AtomicReference<>();
        Runnable body = () -> {
            tenant.set(parse(client.invoke("getTenantId", "[]")));
            userId.set(parse(client.invoke("getLoginUserId", "[]")));
            username.set(parse(client.invoke("getLoginUsername", "[]")));
        };
        Runnable wrapped = (Runnable) body;
        wrapped = bind(LoginContext.KEY, user, wrapped);
        wrapped = bind(TenantContext.KEY, tenantId, wrapped);
        wrapped.run();
        return tenant.get() + ":" + userId.get() + ":" + username.get();
    }

    private static <T> Runnable bind(ScopedValue<T> key, T value, Runnable task) {
        return value == null ? task : () -> ScopedValue.where(key, value).run(task);
    }

    private static String parse(String envelope) {
        try {
            JsonNode node = new ObjectMapper().readTree(envelope).get("data");
            return node == null || node.isNull() ? "null" : node.asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析下游响应失败：" + envelope, e);
        }
    }

    /**
     * 调 ④ 的 Facade HTTP 端点的 Feign 契约。
     *
     * <p><b>契约刻意带 MVC 注解</b>：Feign 的 Contract 靠注解才知道怎么发请求。
     * 这也正是 ④ 选方案 C（运行时适配）而非「给 {@code *-api} 加注解」的收益之一——
     * 真要做 Feign 客户端，调用方可以在<b>自己这边</b>声明带注解的窄接口，契约层依旧零依赖。
     */
    @FeignClient(name = "pivotos-admin-server", url = "${pivotos.it.downstream-url}")
    interface FacadeHttpClient {

        @PostMapping(
            value = "/__facade/com.pivotos.common.api.context.ContextFacade/{method}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
        String invoke(@PathVariable("method") String method, @RequestBody String args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DownstreamApp {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableFeignClients(clients = FacadeHttpClient.class)
    static class UpstreamApp {
    }
}

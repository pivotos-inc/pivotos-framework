package com.pivotos.starter.cloud.alibaba;

import com.pivotos.starter.cloud.alibaba.discovery.AlibabaServiceInstanceProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
// ⚠️ Spring Cloud 5.0（Boot 4 世代）把 DiscoveryClient 从 org.springframework.cloud.client
// 挪到了 org.springframework.cloud.client.discovery —— 旧路径在 5.0.3 里已不存在（编译直接失败）。
// ServiceInstance / LoadBalanced 仍在 org.springframework.cloud.client(.loadbalancer)。
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 真机 IT：注册 → 发现 → 负载均衡 → Feign 跨进程调用，全链路打穿。
 *
 * <p><b>为什么是 {@code *IT}</b>：surefire 默认不收录 {@code *IT}（项目既有口径），
 * 本用例需要 dev Nacos 与已启动的 admin-server 实例，不能进默认回归集。
 * 跑法（凭据走环境变量，不落库）：
 * <pre>
 *   export PIVOTOS_NACOS_SERVER_ADDR=175.24.176.176:8848
 *   export PIVOTOS_NACOS_USERNAME=nacos
 *   export PIVOTOS_NACOS_PASSWORD='...'
 *   mvn test -pl pivotos-starters/pivotos-starter-cloud-alibaba \
 *       -Dtest=AlibabaNacosRealServerIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>前置：至少 1 个 admin-server 以 alibaba 形态注册在 Nacos 上（本 IT 自身不注册，
 * webEnvironment=NONE 不会触发 AutoServiceRegistration）。
 */
@SpringBootTest(
    classes = AlibabaNacosRealServerIT.TestApp.class,
    // 必须是 MOCK 而不是 NONE：SCA 的 AutoServiceRegistration 会 introspect 引用
    // spring-boot web 的 WebServerInitializedEvent，NONE 下连上下文都起不来。
    // MOCK 不启动真实服务器，因此不会发布该事件，也就不会真的注册自己。
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "pivotos.cloud.provider=alibaba",
        "pivotos.cloud.enabled=true",
        "spring.application.name=pivotos-nacos-it",
        // 双保险：本 IT 只做发现与调用，绝不把自己注册进去污染 Nacos
        "spring.cloud.nacos.discovery.register-enabled=false",
        "spring.cloud.nacos.discovery.fail-fast=false",
        "spring.cloud.nacos.discovery.failure-tolerance-enabled=true"
    }
)
class AlibabaNacosRealServerIT {

    private static final String SERVICE_ID = "pivotos-admin-server";

    @Autowired
    private DiscoveryClient discoveryClient;

    @Autowired
    @LoadBalanced
    private RestTemplate loadBalancedRestTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @DynamicPropertySource
    static void nacosProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "true");
        registry.add("spring.cloud.nacos.discovery.server-addr",
            () -> System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848"));
        registry.add("spring.cloud.nacos.discovery.username",
            () -> System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", ""));
        registry.add("spring.cloud.nacos.discovery.password",
            () -> System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", ""));
    }

    @Test
    void 服务发现能拿到真实注册的实例() {
        List<String> services = discoveryClient.getServices();
        assertThat(services).as("Nacos 上应能列出服务（凭据或地址不对时这里是空的）").contains(SERVICE_ID);

        List<ServiceInstance> instances = discoveryClient.getInstances(SERVICE_ID);
        assertThat(instances).as("发现不到实例：admin-server 是否已以 -P alibaba 形态注册？").isNotEmpty();
        instances.forEach(i -> System.out.println("[IT] 发现实例 " + i.getHost() + ":" + i.getPort()
            + " secure=" + i.isSecure() + " uri=" + i.getUri()));
    }

    @Test
    void 负载均衡restTemplate能跨进程打到真实服务() {
        List<ServiceInstance> instances = discoveryClient.getInstances(SERVICE_ID);
        Assumptions.assumeTrue(!instances.isEmpty(), "无实例，跳过跨进程调用");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("username", "admin");
        body.put("password", "admin123");

        String resp = loadBalancedRestTemplate.postForObject(
            "http://" + SERVICE_ID + "/system/auth/login",
            new HttpEntity<>(body, headers), String.class);

        System.out.println("[IT] LB 调用返回：" + resp);
        assertThat(resp).as("经 LoadBalancer 跨进程调用登录接口应拿到 token").contains("token");
    }

    @Test
    void feign客户端能跨进程打到真实服务() {
        List<ServiceInstance> instances = discoveryClient.getInstances(SERVICE_ID);
        Assumptions.assumeTrue(!instances.isEmpty(), "无实例，跳过 Feign 调用");

        AuthClient client = new FeignClientBuilder(applicationContext)
            .forType(AuthClient.class, SERVICE_ID)
            .build();

        Map<String, Object> body = new HashMap<>();
        body.put("username", "admin");
        body.put("password", "admin123");
        String resp = client.login(body);
        System.out.println("[IT] Feign 调用返回：" + resp);
        assertThat(resp).as("Feign 经 Nacos 发现 + LB 调用应拿到 token").contains("token");
    }

    @Test
    void 自研SPI能从Nacos取出实例() {
        AlibabaServiceInstanceProvider provider =
            applicationContext.getBean(AlibabaServiceInstanceProvider.class);
        assertThat(provider.list(SERVICE_ID)).as("自研 SPI 应复用同一份 Nacos 实例数据").isNotEmpty();
    }

    /**
     * 登录接口契约（仅测试用）。刻意保留 {@code @FeignClient}：
     * {@link FeignClientBuilder} 通过服务名走 LoadBalancer，不需要写死 URL。
     */
    @FeignClient(name = SERVICE_ID)
    interface AuthClient {
        @RequestMapping(method = RequestMethod.POST, value = "/system/auth/login",
            consumes = MediaType.APPLICATION_JSON_VALUE)
        String login(@RequestBody Map<String, Object> body);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApp {
        /**
         * 刻意用裸 {@code new RestTemplate()}：Boot 4 里 RestTemplateBuilder 的包路径与
         * 可用形态已变，而 {@code @LoadBalanced} 只关心这是个 RestTemplate，
         * 由 LoadBalancerAutoConfiguration 接管即可。
         */
        @Bean
        @LoadBalanced
        RestTemplate loadBalancedRestTemplate() {
            return new RestTemplate();
        }
    }
}

package com.pivotos.starter.cloud.sc.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.sc.feign.CloudContextFeignAutoConfiguration;
import com.pivotos.starter.cloud.sc.fixture.ProbedFacade;
import com.pivotos.starter.cloud.sc.fixture.ProbedLocalFacade;
import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import com.pivotos.starter.cloud.sc.probe.ContextProbeApp;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.core.context.TraceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份/租户跨进程（跨容器）传播的端到端取证。
 *
 * <p><b>本轮要补的是什么</b>：此前只有两个单测——{@code CloudContextCodecTest} 钉死编解码、
 * {@code CloudContextFeignInterceptorTest} 钉死「出站带哪些头」。两者都停在<b>出站侧</b>，
 * 入站「真的把身份恢复进 ScopedValue 了吗」没有任何证据。本用例把它补上。
 *
 * <p><b>形态</b>：上游（本测试 JVM 内的 Feign 客户端，真实出站）+ 下游（另一个真实 Tomcat 容器，
 * 真实过滤器链 + 回显端点），中间走<b>真实 HTTP</b>。刻意不用 MockMvc：
 * MockMvc 只能证明过滤器被调用，证明不了请求线程里上下文真的绑上了。
 *
 * <p><b>「恢复成功」的判据</b>（缺一都可能被假阳性糊弄过去）：
 * <ol>
 *   <li>下游回显的 userId / username / accountType / tenantId 与上游绑定的值逐一相等；</li>
 *   <li>换 3 组不同身份逐一仍然对应 —— 排除「下游碰巧有个默认值」；</li>
 *   <li>下游执行线程 ≠ 上游线程 —— 排除「同线程 ScopedValue 泄漏」造成的假阳性；</li>
 *   <li>负向：凭证不通时下游<b>全 null 且 HTTP 仍 200</b>，同时入站头仍在 ——
 *       证明是「恢复被拒」而不是「请求没到」。</li>
 * </ol>
 *
 * <p><b>不能当判据的</b>：{@code X-Trace-Id}（TraceIdFilter 独立实现，与身份无关）、
 * Sa-Token 的 {@code StpUtil}（跨进程恢复<b>不建</b> Sa 会话）、启动日志（只证明配置被读到）。
 *
 * <p>restore-* 开关只见于本测试的进程内配置，产品 yml 默认值（全关）一字未改，
 * 进程结束即失效，无需还原。
 */
@SpringBootTest(
    classes = CloudContextPropagationEndToEndTest.UpstreamApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud",
        "pivotos.cloud.sc.proxied=com.pivotos.starter.cloud.sc.fixture.ProbedFacade",
        "pivotos.cloud.context.propagate=true",
        "pivotos.cloud.context.internal-token=" + CloudContextPropagationEndToEndTest.TOKEN
    }
)
class CloudContextPropagationEndToEndTest {

    /** 内部调用凭证：仅存在于本测试的进程内配置，不写入任何 yml */
    static final String TOKEN = "it-internal-token";

    private static ConfigurableApplicationContext downstream;
    private static int downstreamPort;

    /**
     * 静态块先起下游：{@code @DynamicPropertySource} 在 Spring 容器初始化时求值，
     * 那时 {@code @BeforeAll} 还没跑，端口必须在类加载阶段就拿到。
     */
    static {
        downstream = new SpringApplicationBuilder(ContextProbeApp.class)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "logging.level.org.springframework=WARN",
                "pivotos.cloud.enabled=true",
                // 下游只消费 cloud-api 的入站过滤器，provider 与本用例无关（Feign 在上游）
                "pivotos.cloud.provider=local",
                // 受控环境：仅在本进程内开启恢复（产品 yml 里这两个值仍是 false）
                "pivotos.cloud.context.restore-tenant=true",
                "pivotos.cloud.context.restore-login=true",
                "pivotos.cloud.context.internal-token=" + TOKEN)
            .run();
        Integer port = downstream.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("下游探针容器未上报端口（local.server.port 缺失），无法继续验证");
        }
        downstreamPort = port;
    }

    @DynamicPropertySource
    static void feignTarget(DynamicPropertyRegistry registry) {
        registry.add("pivotos.cloud.sc.url", () -> "http://127.0.0.1:" + downstreamPort);
    }

    @AfterAll
    static void stopDownstream() {
        if (downstream != null) {
            downstream.close();
        }
    }

    @Autowired
    private ProbedFacade probedFacade;

    @Autowired
    private CloudProperties upstreamProperties;

    @BeforeEach
    void resetToBaseline() {
        upstreamProperties.getContext().setPropagate(true);
        upstreamProperties.getContext().setInternalToken(TOKEN);
        CloudProperties downstreamProperties = downstream.getBean(CloudProperties.class);
        downstreamProperties.getContext().setRestoreTenant(true);
        downstreamProperties.getContext().setRestoreLogin(true);
        downstreamProperties.getContext().setInternalToken(TOKEN);
    }

    // ============ 正向：恢复真的生效 ============

    static Stream<Arguments> identities() {
        return Stream.of(
            Arguments.of(12L, 1001L, "alice", "sys-user"),
            Arguments.of(7L, 42L, "bob", "app-user"),
            Arguments.of(1L, 9L, "carol", "wx-mini-user"));
    }

    @ParameterizedTest(name = "tenant={0} user={1}/{2} accountType={3}")
    @MethodSource("identities")
    @DisplayName("三组不同身份逐一对应：证明下游读到的是上游传来的值，不是某个默认值")
    void 身份与租户跨容器完整恢复(Long tenantId, Long userId, String username, String accountType) {
        JSONObject echo = probe(tenantId, new LoginUser(userId, username, accountType, tenantId), "trace-e2e-1");

        assertThat(echo.getBoolean("login")).as("下游应处于登录态").isTrue();
        assertThat(echo.getLong("userId")).isEqualTo(userId);
        assertThat(echo.getString("username")).isEqualTo(username);
        assertThat(echo.getString("accountType")).isEqualTo(accountType);
        assertThat(echo.getLong("tenantId")).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("出站头确实抵达下游：tenantId/userId/username/accountType/traceId/internal 六个头齐全")
    void 出站传播头抵达下游() {
        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-2");

        JSONObject headers = echo.getJSONObject("headers");
        assertThat(headers.getString(CloudHeaders.TENANT_ID)).isEqualTo("12");
        assertThat(headers.getString(CloudHeaders.USER_ID)).isEqualTo("1001");
        assertThat(headers.getString(CloudHeaders.USERNAME)).isEqualTo("alice");
        assertThat(headers.getString(CloudHeaders.ACCOUNT_TYPE)).isEqualTo("sys-user");
        assertThat(headers.getString(CloudHeaders.TRACE_ID)).isEqualTo("trace-e2e-2");
        assertThat(headers.getString(CloudHeaders.INTERNAL_TOKEN)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("下游执行线程与上游不同：排除同线程 ScopedValue 泄漏造成的假阳性")
    void 下游在另一个线程执行() {
        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-3");

        assertThat(echo.getString("thread"))
            .as("下游必须在另一个线程，否则「上下文被恢复」可能是同线程残留")
            .isNotBlank()
            .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("上游未登录时：租户仍恢复，登录态不得凭空出现")
    void 上游未登录时下游无登录态() {
        JSONObject echo = probe(33L, null, "trace-e2e-4");

        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("userId")).isNull();
        assertThat(echo.getLong("tenantId")).isEqualTo(33L);
    }

    // ============ 负向：凭证不通时的拒绝证据 ============

    @Test
    @DisplayName("下游未配内部凭证：全 null 且 HTTP 仍 200（静默放行是设计，不是异常）")
    void 下游未配凭证时拒绝恢复() {
        downstream.getBean(CloudProperties.class).getContext().setInternalToken("");

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-5");

        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("tenantId")).isNull();
        assertThat(echo.getLong("userId")).isNull();
        // 头仍然抵达 —— 证明是「拒绝恢复」而不是「请求没到」
        assertThat(echo.getJSONObject("headers").getString(CloudHeaders.USER_ID)).isEqualTo("1001");
        assertThat(echo.getString("traceId")).isEqualTo("trace-e2e-5");
    }

    @Test
    @DisplayName("凭证不匹配：全 null，且能看出上游带的凭证是什么（拒绝而非静默丢弃）")
    void 凭证不匹配时拒绝恢复() {
        downstream.getBean(CloudProperties.class).getContext().setInternalToken("another-token");

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-6");

        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("tenantId")).isNull();
        assertThat(echo.getJSONObject("headers").getString(CloudHeaders.INTERNAL_TOKEN)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("只开 restore-tenant：租户恢复、登录态不恢复")
    void 只恢复租户() {
        downstream.getBean(CloudProperties.class).getContext().setRestoreLogin(false);

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-7");

        assertThat(echo.getLong("tenantId")).isEqualTo(12L);
        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("userId")).isNull();
    }

    @Test
    @DisplayName("只开 restore-login：身份恢复、租户不恢复")
    void 只恢复登录态() {
        downstream.getBean(CloudProperties.class).getContext().setRestoreTenant(false);

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-8");

        assertThat(echo.getBoolean("login")).isTrue();
        assertThat(echo.getLong("userId")).isEqualTo(1001L);
        assertThat(echo.getLong("tenantId")).isNull();
    }

    @Test
    @DisplayName("上游关闭 propagate：连头都不发，下游自然什么都不恢复")
    void 上游关闭出站传播() {
        upstreamProperties.getContext().setPropagate(false);

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-e2e-9");

        assertThat(echo.getJSONObject("headers")).as("出站传播关闭后连内部凭证头都不应出现").isEmpty();
        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("tenantId")).isNull();
    }

    @Test
    @DisplayName("伪造身份头 + 猜错凭证：不得生效，且返回 200（不是 4xx，是设计上的静默放行）")
    void 伪造身份头无效() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + downstreamPort + "/__probe/echo?v=forged"))
            .header(CloudHeaders.TENANT_ID, "8888")
            .header(CloudHeaders.USER_ID, "9999")
            .header(CloudHeaders.USERNAME, "attacker")
            .header(CloudHeaders.INTERNAL_TOKEN, "guess")
            .GET()
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("拒绝恢复必须是静默放行（200），不能暴露成 4xx/5xx").isEqualTo(200);
        JSONObject echo = JSON.parseObject(response.body());
        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("userId")).isNull();
        assertThat(echo.getLong("tenantId")).isNull();
        // 伪造者猜对了头名也拿不到身份，但链路 ID 由 TraceIdFilter 独立处理，不受此约束
        assertThat(echo.getJSONObject("headers").getString(CloudHeaders.USER_ID)).isEqualTo("9999");
    }

    // ============ 支撑 ============

    /**
     * 在给定的上下文绑定下发起一次真实的 Feign 跨容器调用，并返回下游回显。
     *
     * <p>刻意逐个字段用 {@code ScopedValue.where} 嵌套包裹：绑什么就传什么，
     * 不绑的字段必须不出头（这也是 {@code toHeaders} 的契约）。
     */
    private JSONObject probe(Long tenantId, LoginUser user, String traceId) {
        AtomicReference<String> response = new AtomicReference<>();
        Runnable task = () -> response.set(probedFacade.echo("probe"));

        Runnable wrapped = task;
        if (user != null) {
            Runnable previous = wrapped;
            wrapped = () -> ScopedValue.where(LoginContext.KEY, user).run(previous);
        }
        if (tenantId != null) {
            Runnable previous = wrapped;
            wrapped = () -> ScopedValue.where(TenantContext.KEY, tenantId).run(previous);
        }
        if (traceId != null) {
            Runnable previous = wrapped;
            wrapped = () -> ScopedValue.where(TraceContext.KEY, traceId).run(previous);
        }
        wrapped.run();

        assertThat(response.get()).as("下游回显不应为空（调用失败会在此处炸出来）").isNotBlank();
        return JSON.parseObject(response.get());
    }

    /**
     * 上游形态：与 {@code FeignFacadeNoCircuitBreakerTest} 同一套装配口径
     * （{@code @ImportAutoConfiguration}，不用 {@code @EnableAutoConfiguration}——
     * NONE 环境下它会把需要 servlet 的自动配置拉起来导致 NoClassDefFoundError）。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CloudProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
        // 出站身份传播的装配必须显式带上：本测试用 @ImportAutoConfiguration（不用 @EnableAutoConfiguration），
        // 漏了它就一个身份头都不发——这正是「调用通了但身份没传」最难自查的一类故障。
        CloudContextFeignAutoConfiguration.class,
        ScCloudAutoConfiguration.class
    })
    static class UpstreamApp {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        static BeanDefinitionRegistryPostProcessor probedLocalFacade() {
            return registry -> registry.registerBeanDefinition("probedFacade",
                new RootBeanDefinition(ProbedLocalFacade.class));
        }
    }
}

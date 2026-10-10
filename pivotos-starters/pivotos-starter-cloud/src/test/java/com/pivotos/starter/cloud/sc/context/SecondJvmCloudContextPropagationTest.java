package com.pivotos.starter.cloud.sc.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.sc.config.ScCloudAutoConfiguration;
import com.pivotos.starter.cloud.sc.feign.CloudContextFeignAutoConfiguration;
import com.pivotos.starter.cloud.sc.fixture.ProbedFacade;
import com.pivotos.starter.cloud.sc.fixture.ProbedLocalFacade;
import com.pivotos.starter.cloud.sc.probe.ContextProbeApp;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.core.context.TraceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>真·第二个 JVM</b>的身份/租户传播复验（V3-S3 第 ① 项）。
 *
 * <p><b>与 L5 的差别只有一处，但很关键</b>：L5 的下游是<b>同一个 JVM 里的另一个容器</b>
 * （{@code SpringApplicationBuilder} 起的 {@code ContextProbeApp}）。
 * 单 JVM 双容器已经能证明「不是同线程泄漏」，但它<b>证不了同进程共享效应</b>——
 * ScopedValue 的绑定、静态缓存、类加载器共享都是同一个进程的事实。
 * 本用例把下游放进一个真正独立的 {@code java} 进程：JIT、类加载器、静态状态全部互不共享。
 *
 * <p><b>判据（沿用并加严 L5 体系）</b>：
 * <ol>
 *   <li>下游回显的 userId / username / accountType / tenantId 与上游绑定值逐一相等；</li>
 *   <li>三组身份逐一对应（排除默认值假阳性）；</li>
 *   <li><b>PID 与本进程不同</b> —— 这才是「第二个 JVM」的唯一硬证据；线程名不同只是辅助；</li>
 *   <li>负向：凭证不通时全 null、HTTP 仍 200、且入站头依旧在（是「拒绝恢复」不是「没到」）。</li>
 * </ol>
 *
 * <p><b>为什么用进程参数而不是写在 yml 里</b>：restore-* / internal-token 是<b>受控环境变量</b>，
 * 产品 yaml 的默认值（全关、空凭证）一字未改；进程结束即失效，无需还原。
 *
 * <p><b>运维纪律</b>：本用例只 {@code destroy} 自己拉起的那个进程；
 * 端口用 {@code ServerSocket(0)} 取空闲口再交给子进程，避免撞端口。
 */
@SpringBootTest(
    classes = SecondJvmCloudContextPropagationTest.UpstreamApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=cloud",
        "pivotos.cloud.sc.proxied=com.pivotos.starter.cloud.sc.fixture.ProbedFacade",
        "pivotos.cloud.context.propagate=true",
        "pivotos.cloud.context.internal-token=" + SecondJvmCloudContextPropagationTest.TOKEN
    })
class SecondJvmCloudContextPropagationTest {

    static final String TOKEN = "second-jvm-it-token";

    private static Process downstream;
    private static int downstreamPort;
    private static Path logFile;

    /**
     * 静态块里起子进程：{@code @DynamicPropertySource} 在容器初始化时求值，
     * 那时 {@code @BeforeAll} 还没跑，端口与进程必须在类加载阶段就就绪。
     */
    static {
        try {
            logFile = Files.createTempFile("second-jvm-downstream", ".log");
            downstreamPort = freePort();
            downstream = startDownstream(downstreamPort, logFile);
            awaitReady(downstreamPort, downstream, logFile);
        } catch (Exception e) {
            throw new IllegalStateException("第二个 JVM（下游探针）启动失败", e);
        }
    }

    @DynamicPropertySource
    static void feignTarget(DynamicPropertyRegistry registry) {
        registry.add("pivotos.cloud.sc.url", () -> "http://127.0.0.1:" + downstreamPort);
    }

    @AfterAll
    static void stopDownstream() {
        if (downstream != null) {
            downstream.destroy();
            try {
                downstream.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> identities() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(12L, 1001L, "alice", "sys-user"),
            org.junit.jupiter.params.provider.Arguments.of(7L, 42L, "bob", "app-user"),
            org.junit.jupiter.params.provider.Arguments.of(1L, 9L, "carol", "wx-mini-user"));
    }

    @ParameterizedTest(name = "tenant={0} user={1}/{2} accountType={3}")
    @MethodSource("identities")
    @DisplayName("三组身份跨【第二个 JVM】逐一对应")
    void identity_is_restored_in_the_second_jvm(Long tenantId, Long userId, String username, String accountType) {
        JSONObject echo = probe(tenantId, new LoginUser(userId, username, accountType, tenantId), "trace-1");

        assertThat(echo.getLong("tenantId")).isEqualTo(tenantId);
        assertThat(echo.getLong("userId")).isEqualTo(userId);
        assertThat(echo.getString("username")).isEqualTo(username);
        assertThat(echo.getString("accountType")).isEqualTo(accountType);
    }

    @Test
    @DisplayName("六头全部抵达另一个进程（含 X-PivotOS-Internal 与链路头）")
    void all_headers_arrive_in_another_process() {
        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-abc");

        JSONObject headers = echo.getJSONObject("headers");
        assertThat(headers.getString(CloudHeaders.TENANT_ID)).isEqualTo("12");
        assertThat(headers.getString(CloudHeaders.USER_ID)).isEqualTo("1001");
        assertThat(headers.getString(CloudHeaders.USERNAME)).isEqualTo("alice");
        assertThat(headers.getString(CloudHeaders.ACCOUNT_TYPE)).isEqualTo("sys-user");
        assertThat(headers.getString(CloudHeaders.TRACE_ID)).isEqualTo("trace-abc");
        assertThat(headers.getString(CloudHeaders.INTERNAL_TOKEN)).isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("下游 PID 必须不同于本进程（这是「第二个 JVM」唯一的硬证据）")
    void downstream_runs_in_another_process() {
        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-xyz");

        long ownPid = ProcessHandle.current().pid();
        assertThat(echo.getLong("pid"))
            .as("下游 PID 与本进程相同 ⇒ 根本没跨进程，所谓「第二个 JVM」是假的")
            .isNotEqualTo(ownPid)
            .isEqualTo(downstream.pid());
        assertThat(echo.getString("thread")).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("凭证不匹配：跨进程也只恢复到「没有身份」，且 HTTP 仍 200")
    void wrong_token_never_restores_identity_across_processes() {
        upstreamProperties.getContext().setInternalToken("wrong-token");

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-1");

        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("userId")).isNull();
        assertThat(echo.getLong("tenantId")).isNull();
        // 头还是要到的：证明是「拒绝恢复」而不是「请求没到另一个进程」
        assertThat(echo.getJSONObject("headers").getString(CloudHeaders.USER_ID)).isEqualTo("1001");
    }

    @Test
    @DisplayName("未配凭证：即使在另一个 JVM 里也不恢复（没配 ≠ 放行）")
    void blank_token_restores_nothing() {
        upstreamProperties.getContext().setInternalToken("");

        JSONObject echo = probe(12L, new LoginUser(1001L, "alice", "sys-user", 12L), "trace-1");

        assertThat(echo.getBoolean("login")).isFalse();
        assertThat(echo.getLong("tenantId")).isNull();
        assertThat(echo.getLong("userId")).isNull();
    }

    // ============ 支撑 ============

    private JSONObject probe(Long tenantId, LoginUser user, String traceId) {
        AtomicReference<String> response = new AtomicReference<>();
        Runnable wrapped = () -> response.set(probedFacade.echo("probe"));
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

        assertThat(response.get()).as("下游（第二个 JVM）未回显，先看子进程日志：%s", tail(logFile)).isNotBlank();
        return JSON.parseObject(response.get());
    }

    /** 取一个当前空闲的端口交给子进程（拿到即关，极短窗口内竞争的概率可忽略） */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Process startDownstream(int port, Path log) throws IOException {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        command.add("-cp");
        command.add(classpath());
        command.add(ContextProbeApp.class.getName());
        command.add("--server.port=" + port);
        command.add("--spring.main.banner-mode=off");
        command.add("--logging.level.root=WARN");
        command.add("--pivotos.cloud.enabled=true");
        command.add("--pivotos.cloud.provider=local");
        command.add("--pivotos.cloud.context.restore-tenant=true");
        command.add("--pivotos.cloud.context.restore-login=true");
        command.add("--pivotos.cloud.context.internal-token=" + TOKEN);

        return new ProcessBuilder(command)
            // 输出必须落盘：管道缓冲区写满会把子进程卡死，这是拉起外部 JVM 最常见的假死锁
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    }

    /**
     * 推导子进程 classpath。
     *
     * <p>首选从测试类加载器取全量 URL——surefire 的 manifest-only jar 会让
     * {@code java.class.path} 只剩一个引导包（它靠 manifest 的 Class-Path 展开，
     * 直接当列表用会缺项）；确实拿不到时才退回 {@code java.class.path}。
     */
    private static String classpath() {
        Set<String> paths = new LinkedHashSet<>();
        ClassLoader loader = SecondJvmCloudContextPropagationTest.class.getClassLoader();
        if (loader instanceof URLClassLoader urlClassLoader) {
            for (URL url : urlClassLoader.getURLs()) {
                paths.add(url.getFile());
            }
        }
        if (paths.isEmpty()) {
            paths.add(System.getProperty("java.class.path"));
        }
        return String.join(java.io.File.pathSeparator, paths);
    }

    /** 轮询下游探活：Tomcat 与容器初始化需要时间，这里只看 HTTP 200 */
    private static void awaitReady(int port, Process process, Path log) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (!process.isAlive()) {
                throw new IllegalStateException("子进程已退出，日志尾部：" + tail(log));
            }
            if (probeOnce(port)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("等待第二个 JVM 就绪超时，日志尾部：" + tail(log));
    }

    private static boolean probeOnce(int port) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/__probe/echo").openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(1000);
            return connection.getResponseCode() == 200;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String tail(Path log) {
        try {
            if (log == null || !Files.exists(log)) {
                return "(日志缺失)";
            }
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
        } catch (IOException e) {
            return "(日志读取失败：" + e.getMessage() + ")";
        }
    }

    /**
     * 上游形态：与 L5 及 {@code FeignFacadeNoCircuitBreakerTest} 同一装配口径。
     *
     * <p>显式清单里必须带 {@link CloudContextFeignAutoConfiguration}——漏了它就一个身份头都不发，
     * 而「调用通了 = 绿灯」会掩盖这一点（S133/L5 的同款坑）。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CloudProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
        FeignAutoConfiguration.class,
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

package com.pivotos.starter.cloud.alibaba.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 动态刷新的真机取证（V3-S3 第 ⑥ 项，真机部分）。
 *
 * <p><b>要证的那句话</b>：「在 Nacos 上改配置，<b>不重启</b>进程就能生效」。
 * 离线部分（HTTP 事件 → 重绑定）由 {@code ConfigRefreshMechanismTest} 覆盖，
 * 这里补的是离线根本验不了的那一段：<b>Nacos 侧的长轮询变更真的会触发刷新</b>。
 *
 * <p><b>dataId 在本轮测试期间不会被删除</b>（避免跑 IT 还要申请写权限之外的动作），
 * 采用随机后缀 + 发布后恢复成对".refresh=0\n.txt"的收敛策略。
 *
 * <p><b>跑法</b>（凭据只走环境变量，不落任何文件/git）：
 * <pre>
 *   export PIVOTOS_NACOS_SERVER_ADDR=175.24.176.176:8848
 *   export PIVOTOS_NACOS_USERNAME=nacos
 *   export PIVOTOS_NACOS_PASSWORD='...'
 *   mvn test -pl pivotos-starters/pivotos-starter-cloud-alibaba \
 *       -Dtest=NacosConfigRefreshRealServerIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class NacosConfigRefreshRealServerIT {

    @BeforeAll
    static void requireCredentials() {
        Assumptions.assumeTrue(!System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "").isBlank(),
            "需要 PIVOTOS_NACOS_SERVER_ADDR（dev = 175.24.176.176:8848）");
        Assumptions.assumeTrue(!System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", "").isBlank(),
            "需要 PIVOTOS_NACOS_PASSWORD");
    }

    @Test
    @DisplayName("改 Nacos 上的 dataId 内容，进程不重启即可读到新值")
    void change_on_nacos_takes_effect_without_restart() throws Exception {
        String dataId = "pivotos.refresh.it." + ThreadLocalRandom.current().nextInt(100000, 999999) + ".yaml";

        // 顺序很重要：先把 dataId 建出来再起进程。
        // 「启动时 dataId 还不存在」的情形 optional 前缀会静默跳过，Nacos 侧也就不会为它建监听，
        // 之后再发布内容也不会触发刷新——那是另一件事（本 IT 不覆盖），这里测的是常态：配置本来就在。
        publishConfig(dataId, probeContent("before"));

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(RefreshItApp.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "spring.application.name=pivotos-refresh-it",
                "logging.level.root=WARN",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.server-addr="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848"),
                "spring.cloud.nacos.config.username="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos"),
                "spring.cloud.nacos.config.password="
                    + System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", ""),
                "spring.cloud.nacos.config.file-extension=yaml",
                "spring.cloud.nacos.config.refresh-enabled=true",
                "spring.config.import[0]=optional:nacos:" + dataId)
            .run();

        try {
            RefreshProbeProperties probe = ctx.getBean(RefreshProbeProperties.class);
            assertThat(awaitValue(probe, "before", Duration.ofSeconds(20)))
                .as("基准值都没读到：配置中心这一段本身没通，后面的刷新结论不成立")
                .isTrue();

            publishConfig(dataId, probeContent("after"));
            assertThat(awaitValue(probe, "after", Duration.ofSeconds(30)))
                .as("在 Nacos 上改了内容但进程没读到——动态刷新没生效，只能重启")
                .isTrue();
        } finally {
            ctx.close();
            deleteConfig(dataId);
        }
    }

    /** 轮询而非 sleep 固定时长：刷新依赖长轮询（默认约 10s），写死 sleep 会让 IT 变成赌运气 */
    private boolean awaitValue(RefreshProbeProperties probe, String expected, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(probe.getValue())) {
                return true;
            }
            Thread.sleep(300L);
        }
        return false;
    }

    private static String probeContent(String value) {
        return "pivotos:\n  refresh:\n    probe:\n      value: " + value + "\n";
    }

    /**
     * Nacos 3.x 控制台 API（v3）：登录换 token → 带 Bearer 发布配置。
     *
     * <p><b>两个坑（真机实测，勿重踩）</b>：
     * <ol>
     *   <li><b>不能把 username/password 当 query 参数传</b>：Nacos 3.x（dev 实测就是 3.x）
     *       要先 {@code POST /nacos/v3/auth/user/login} 换 accessToken，再在发布接口带
     *       {@code Authorization: Bearer <token>}。直接带 username 会拿
     *       {@code 401 User not found}——而这个报错和「口令真错了」长得一模一样，
     *       不知道的话会一直怀疑口令。</li>
     *   <li><b>口令必须 URL 编码</b>：口令里带 {@code #} 时，拼进 query/表单会被当成
     *       fragment/截断，服务端收到的是半截口令，同样报 401。SDK（注册发现那条路）
     *       自己会编码所以不受影响，手拼 Open API 的这条路必须显式编码。</li>
     * </ol>
     */
    private static void publishConfig(String dataId, String content) throws Exception {
        String base = "http://" + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848");
        String username = System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos");
        String password = System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", "");

        HttpClient client = HttpClient.newBuilder().build();

        String loginBody = "username=" + encode(username) + "&password=" + encode(password);
        HttpResponse<String> loginResponse = client.send(
            HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(20))
                .uri(URI.create(base + "/nacos/v3/auth/user/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode())
            .as("Nacos 登录失败（v3 登录接口）：%s", loginResponse.body())
            .isEqualTo(200);

        JsonNode loginJson = new ObjectMapper().readTree(loginResponse.body());
        JsonNode accessToken = loginJson.get("accessToken") != null
            ? loginJson.get("accessToken")
            : loginJson.path("data").get("accessToken");
        assertThat(accessToken).as("登录响应里没有 accessToken：%s", loginResponse.body()).isNotNull();

        String publishBody = "dataId=" + encode(dataId)
            + "&groupName=DEFAULT_GROUP"
            + "&type=yaml"
            + "&content=" + encode(content);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(20))
                .uri(URI.create(base + "/nacos/v3/admin/cs/config"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer " + accessToken.asText())
                .POST(HttpRequest.BodyPublishers.ofString(publishBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
            .as("发布配置失败：%s（dataId=%s，口令 env=%s）",
                response.body(), dataId, password.isBlank() ? "空" : "已注入")
            .isEqualTo(200);
    }

    /** 收尾删掉临时 dataId：真机 IT 不留垃圾，否则 dev Nacos 上会越堆越多 */
    private static void deleteConfig(String dataId) {
        try {
            String base = "http://" + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848");
            String token = loginToken();
            HttpClient client = HttpClient.newBuilder().build();
            client.send(
                HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(10))
                    .uri(URI.create(base + "/nacos/v3/admin/cs/config?dataId=" + encode(dataId)
                        + "&groupName=DEFAULT_GROUP"))
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // 清理失败不影响结论：dataId 带随机后缀，下次不会串味
        }
    }

    private static String loginToken() throws Exception {
        String base = "http://" + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848");
        HttpClient client = HttpClient.newBuilder().build();
        String body = "username=" + encode(System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos"))
            + "&password=" + encode(System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", ""));
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(10))
                .uri(URI.create(base + "/nacos/v3/auth/user/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        JsonNode json = new ObjectMapper().readTree(response.body());
        JsonNode token = json.get("accessToken") != null ? json.get("accessToken") : json.path("data").get("accessToken");
        return token == null ? "" : token.asText();
    }

    private static String encode(String raw) {
        return java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    @Data
    @ConfigurationProperties(prefix = "pivotos.refresh.probe")
    static class RefreshProbeProperties {
        private String value = "unset";
    }

    /**
     * <b>刻意用完整自动配置而不是手工 @{@code ImportAutoConfiguration} 清单</b>。
     *
     * <p>第一版挑了三个「看起来够用」的配置（ConfigurationProperties / Rebinder / Refresh），
     * 结果进程启动正常、配置也读到了，但改远端内容永远不生效——因为真正监听 Nacos 变更并
     * 发布刷新事件的是 SCA 自己的 {@code NacosContextRefresher}，它不在这份清单里。
     * 症状是「读得到、刷不动」，与「长轮询没开」长得一样，极易误判。
     *
     * <p>教训与 L5 那条同款：<b>手工维护自动配置清单 = 每加一个能力都要回头核对所有清单</b>，
     * 凡是「要复现真实运行形态」的测试，就应该用 {@code @EnableAutoConfiguration}。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(RefreshProbeProperties.class)
    static class RefreshItApp {
    }
}

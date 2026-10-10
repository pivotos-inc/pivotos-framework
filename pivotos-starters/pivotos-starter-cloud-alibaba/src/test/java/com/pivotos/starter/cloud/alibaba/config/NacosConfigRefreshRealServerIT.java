package com.pivotos.starter.cloud.alibaba.config;

import lombok.Data;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
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
                "spring.config.import[0]=optional:nacos:" + dataId)
            .run();

        try {
            RefreshProbeProperties probe = ctx.getBean(RefreshProbeProperties.class);
            publishConfig(dataId, probeContent("before"));
            assertThat(awaitValue(probe, "before", Duration.ofSeconds(20)))
                .as("先把基准值写进去（这一段的目的是确认远端内容已经能被进程读到）")
                .isTrue();

            publishConfig(dataId, probeContent("after"));
            assertThat(awaitValue(probe, "after", Duration.ofSeconds(20)))
                .as("在 Nacos 上改了内容但进程没读到——动态刷新没生效，只能重启")
                .isTrue();
        } finally {
            ctx.close();
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

    /** Nacos 3.x 控制台 API（v3）：发布配置 */
    private static void publishConfig(String dataId, String content) throws Exception {
        String base = "http://" + System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "127.0.0.1:8848");
        String username = System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos");
        String password = System.getenv().getOrDefault("PIVOTOS_NACOS_PASSWORD", "");

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(20))
            .uri(URI.create(base + "/nacos/v3/admin/cs/config?"
                + "dataId=" + dataId
                + "&groupName=DEFAULT_GROUP"
                + "&content=" + java.net.URLEncoder.encode(content, StandardCharsets.UTF_8)
                + "&type=yaml"
                + "&username=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8)))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("NA", "auth")
            .POST(HttpRequest.BodyPublishers.noBody());

        HttpClient client = HttpClient.newBuilder().build();
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
            .as("发布配置失败：%s（口令 env=%s）", response.body(), password.isBlank() ? "空" : "已注入")
            .isEqualTo(200);
    }

    @Data
    @ConfigurationProperties(prefix = "pivotos.refresh.probe")
    static class RefreshProbeProperties {
        private String value = "unset";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshProbeProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        ConfigurationPropertiesRebinderAutoConfiguration.class,
        RefreshAutoConfiguration.class
    })
    static class RefreshItApp {
    }
}

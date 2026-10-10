package com.pivotos.starter.cloud.alibaba;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nacos 配置中心真机 IT（V3-S2 L6）。
 *
 * <p><b>为什么是 {@code *IT}</b>：需要 dev Nacos（175.24.176.176:8848）与真实凭据，
 * 不能进默认回归集。跑法（凭据走环境变量，不落库）：
 * <pre>
 *   export PIVOTOS_NACOS_SERVER_ADDR=175.24.176.176:8848
 *   export PIVOTOS_NACOS_USERNAME=nacos
 *   export PIVOTOS_NACOS_PASSWORD='...'
 *   mvn test -pl pivotos-starters/pivotos-starter-cloud-alibaba -am \
 *       -Dtest=NacosConfigRealServerIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>Nacos 3.x 事实（本轮实测，与命名侧一致）</b>：v1 配置 API 已不存在
 * （{@code GET /nacos/v1/cs/configs} → 404 No endpoint），只有 v3：
 * 登录 {@code POST /nacos/v3/auth/user/login}、发布 {@code POST /nacos/v3/admin/cs/config}、
 * 读取 {@code GET /nacos/v3/admin/cs/config}、删除 {@code DELETE /nacos/v3/admin/cs/config}。
 * 因此本 IT 的夹具（发布/删除 dataId）走 v3 HTTP API，不依赖 nacos-client 的写入能力。
 *
 * <p>夹具自清：{@code @AfterAll} 删除两个临时 dataId 并复核确已消失，
 * 删不掉就抛错（宁可红也不能污染 dev Nacos）。
 */
class NacosConfigRealServerIT {

    private static final String SERVER_ADDR = System.getenv().getOrDefault("PIVOTOS_NACOS_SERVER_ADDR", "");
    private static final String USERNAME = System.getenv().getOrDefault("PIVOTOS_NACOS_USERNAME", "nacos");
    private static final String PASSWORD = System.getenv("PIVOTOS_NACOS_PASSWORD");
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String NAMESPACE_ID = "";   // 空即 public

    private static final String TAG = "pivotos-config-it-" + Long.toHexString(System.nanoTime());
    private static final String DATA_ID_MAIN = TAG + ".yaml";
    private static final String DATA_ID_DEV = TAG + "-dev.yaml";

    private static final String PROBE_MAIN = "probe-" + Long.toHexString(System.nanoTime());
    private static final String PROBE_DEV = "probe-dev-" + Long.toHexString(System.nanoTime());

    private static final HttpClient HTTP = HttpClient.newBuilder().build();

    @BeforeAll
    static void 准备夹具() {
        Assumptions.assumeTrue(!SERVER_ADDR.isBlank(),
            "需要设置 PIVOTOS_NACOS_SERVER_ADDR（dev = 175.24.176.176:8848）");
        Assumptions.assumeTrue(PASSWORD != null && !PASSWORD.isBlank(),
            "需要设置 PIVOTOS_NACOS_PASSWORD（凭据只走环境变量，不落 git）");

        String token = login();
        publish(token, DATA_ID_MAIN, "pivotos.nacos.probe: " + PROBE_MAIN);
        publish(token, DATA_ID_DEV, "pivotos.nacos.probe.dev: " + PROBE_DEV);
        System.out.println("[IT] 已发布夹具 dataId： " + DATA_ID_MAIN + " / " + DATA_ID_DEV);
    }

    @AfterAll
    static void 清理夹具() {
        if (SERVER_ADDR.isBlank() || PASSWORD == null || PASSWORD.isBlank()) {
            return;
        }
        try {
            String token = login();
            for (String dataId : new String[]{DATA_ID_MAIN, DATA_ID_DEV}) {
                delete(token, dataId);
                String body = get(token, dataId);
                if (!body.contains("resource not found")) {
                    throw new IllegalStateException("夹具未清理干净，dev Nacos 上仍存在 dataId=" + dataId + "，返回：" + body);
                }
            }
            System.out.println("[IT] 夹具已清理并复核： " + DATA_ID_MAIN + " / " + DATA_ID_DEV);
        } catch (Exception e) {
            throw new IllegalStateException("清理 dev Nacos 夹具失败，请手工删除 dataId=" + DATA_ID_MAIN + " / " + DATA_ID_DEV, e);
        }
    }

    @Test
    void 真机能读到Nacos下发的配置() {
        // ⚠️ DATA_ID_MAIN / DATA_ID_DEV 本身已含 .yaml 后缀，这里千万别再拼一次
        try (ConfigurableApplicationContext ctx = run(PASSWORD,
            "optional:nacos:" + DATA_ID_MAIN,
            "optional:nacos:" + DATA_ID_DEV)) {
            assertTrue(ctx.isActive(), "连上真实 Nacos 时应用必须起得来");
            assertEquals(PROBE_MAIN, awaitProbe(ctx, "pivotos.nacos.probe"), "主 dataId 的内容没读到");
        }
    }

    @Test
    void 环境覆盖dataId与占位符写法在真机上同样生效() {
        try (ConfigurableApplicationContext ctx = run(PASSWORD,
            "optional:nacos:" + TAG + ".yaml",
            // 与 admin-server application.yml 完全同形：环境后缀由占位符给出，默认 dev
            "optional:nacos:" + TAG + "-${PIVOTOS_NACOS_CONFIG_ENV:dev}.yaml")) {
            String mainProbe = awaitProbe(ctx, "pivotos.nacos.probe");
            String devProbe = awaitProbe(ctx, "pivotos.nacos.probe.dev");
            assertEquals(PROBE_MAIN, mainProbe, "公共 dataId 应生效");
            assertEquals(PROBE_DEV, devProbe,
                "环境覆盖 dataId 应生效——不生效即说明 import 阶段的 ${...} 占位符没被解析");
        }
    }

    @Test
    void 真机上dataId不存在时仍能起服() {
        try (ConfigurableApplicationContext ctx = run(PASSWORD,
            "optional:nacos:pivotos-config-it-missing-" + Long.toHexString(System.nanoTime()) + ".yaml")) {
            assertTrue(ctx.isActive(), "Nacos 可达但 dataId 不存在时也必须起服");
            assertNull(ctx.getEnvironment().getProperty("pivotos.nacos.probe"), "没下发就不该有值");
        }
    }

    @Test
    void 真机上口令错误时仍能起服() {
        try (ConfigurableApplicationContext ctx = run("definitely-wrong-password",
            "optional:nacos:" + DATA_ID_MAIN)) {
            assertTrue(ctx.isActive(), "Nacos 鉴权失败（403）时也必须起服——这是本轮硬要求");
        }
    }

    // ------------------------------------------------------------------ 应用侧

    private static ConfigurableApplicationContext run(String password, String... imports) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(TestApp.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "spring.application.name=pivotos-nacos-config-it",
                "spring.cloud.nacos.config.server-addr=" + SERVER_ADDR,
                "spring.cloud.nacos.config.username=" + USERNAME,
                "spring.cloud.nacos.config.password=" + password,
                "spring.cloud.nacos.config.timeout=5000",
                "spring.cloud.nacos.discovery.enabled=false",
                "logging.level.root=WARN"
            );
        for (int i = 0; i < imports.length; i++) {
            builder.properties("spring.config.import[" + i + "]=" + imports[i]);
        }
        return builder.run();
    }

    /** Nacos 下发有秒级延迟，给 5 次机会再判定失败。 */
    private static String awaitProbe(ConfigurableApplicationContext ctx, String key) {
        String value = null;
        for (int i = 0; i < 5 && value == null; i++) {
            value = ctx.getEnvironment().getProperty(key);
            if (value == null) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApp {
    }

    // ------------------------------------------------------------------ Nacos v3 API

    private static String login() {
        String body = form("username", USERNAME, "password", PASSWORD);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url("/nacos/v3/auth/user/login")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
        assertTrue(resp.body().contains("accessToken"), "Nacos v3 登录失败，返回：" + resp.body());
        return jsonField(resp.body(), "accessToken");
    }

    private static void publish(String token, String dataId, String content) {
        String body = form("dataId", dataId, "groupName", GROUP, "namespaceId", NAMESPACE_ID,
            "content", content, "type", "yaml", "accessToken", token);
        HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url("/nacos/v3/admin/cs/config")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
        assertTrue(resp.body().contains("\"success\""), "发布 dataId=" + dataId + " 失败：" + resp.body());
    }

    private static void delete(String token, String dataId) {
        String query = query("dataId", dataId, "groupName", GROUP, "namespaceId", NAMESPACE_ID, "accessToken", token);
        send(HttpRequest.newBuilder(URI.create(url("/nacos/v3/admin/cs/config") + "?" + query))
            .DELETE()
            .build());
    }

    private static String get(String token, String dataId) {
        String query = query("dataId", dataId, "groupName", GROUP, "namespaceId", NAMESPACE_ID, "accessToken", token);
        return send(HttpRequest.newBuilder(URI.create(url("/nacos/v3/admin/cs/config") + "?" + query))
            .GET()
            .build()).body();
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            assertFalse(resp.statusCode() >= 500, "Nacos 返回 5xx：" + resp.body());
            return resp;
        } catch (IOException e) {
            throw new IllegalStateException("调用 Nacos 失败：" + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Nacos 被中断：" + request.uri(), e);
        }
    }

    private static String url(String path) {
        return "http://" + SERVER_ADDR + path;
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static String query(String... kv) {
        return form(kv);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 极简 JSON 取值：Nacos 返回体是扁平结构，不值得为此引入三方 JSON 依赖。 */
    private static String jsonField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("JSON 里找不到字段 " + field + "：" + json);
        }
        int end = json.indexOf('"', start + needle.length());
        return json.substring(start + needle.length(), end);
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}

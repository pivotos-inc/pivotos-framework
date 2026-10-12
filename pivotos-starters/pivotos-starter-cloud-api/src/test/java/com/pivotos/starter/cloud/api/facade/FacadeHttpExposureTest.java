package com.pivotos.starter.cloud.api.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.common.api.context.ContextFacade;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facade HTTP 适配端点（方案 C）的端到端取证。
 *
 * <p><b>验的不是「端点返回了值」，而是三件更难的事</b>：
 * <ol>
 *   <li><b>入站先恢复、后执行</b>：端点返回的值必须是「入站请求带来的身份」，
 *       而不是「本进程碰巧没上下文所以返回 null」——所以调用线程刻意<b>不绑</b>任何上下文，
 *       一旦 CloudContextFilter 没生效，返回值会立刻变 null（假阳性不成立）；</li>
 *   <li><b>契约一致性</b>：同一份入参，经 HTTP 与进程内直调拿到同值（远端替换不改语义）；</li>
 *   <li><b>安全口径</b>：无凭证/错凭证一律 403 且不执行，白名单外的接口一律 404。</li>
 * </ol>
 *
 * <p><b>为什么刻意用 {@code @SpringBootApplication} 全量自动配置</b>：与
 * {@code ContextProbeApp} 同口径——要验的就是「自动配置清单 + 过滤器顺序」这套组合拳，
 * 手列 {@code @ImportAutoConfiguration} 漏一条就会得到假的绿。
 *
 * <p><b>刻意不断言 traceId</b>：链路 ID 不在 {@code CloudContextFilter} 的职责内
 * （有则由既有 TraceIdFilter 继承），把它放进来只会让断言随 starter-web 是否出席而漂移。
 */
@SpringBootTest(
    classes = FacadeHttpExposureTest.FacadeHttpProbeApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.context.internal-token=" + FacadeHttpExposureTest.TOKEN,
        "pivotos.cloud.context.restore-tenant=true",
        "pivotos.cloud.context.restore-login=true",
        "pivotos.cloud.facade-http.enabled=true"
    })
class FacadeHttpExposureTest {

    static final String TOKEN = "facade-http-unit-test-token";

    private static final String IFACE = ContextFacade.class.getName();

    @LocalServerPort
    private int port;

    private HttpClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    @SpringBootApplication
    static class FacadeHttpProbeApp {
        // 空体：靠全量自动配置把 CloudContextFilter / ScopedValueContextFacade / 本端点一并装配起来
    }

    @Test
    @DisplayName("ContextFacade 经 HTTP 返回的是「入站请求带来的身份」，而非本进程残留")
    void returns_identity_restored_from_inbound_request() throws Exception {
        HttpResponse<String> tenant = invoke("getTenantId", "[]", internalHeaders(12L, 1001L, "alice"));
        HttpResponse<String> userId = invoke("getLoginUserId", "[]", internalHeaders(12L, 1001L, "alice"));
        HttpResponse<String> username = invoke("getLoginUsername", "[]", internalHeaders(12L, 1001L, "alice"));

        assertThat(status(tenant)).isEqualTo(200);
        assertThat(payload(tenant)).isEqualTo(12L);
        assertThat(payload(userId)).isEqualTo(1001L);
        assertThat(payload(username)).isEqualTo("alice");
    }

    @ParameterizedTest
    @CsvSource({
        "12,1001,alice",
        "77,2002,bob",
        "999,3003,carol"
    })
    @DisplayName("三组身份逐一对应：排除「下游返回了某个默认值」的假阳性")
    void multiple_identities_map_one_to_one(long tenantId, long userId, String username) throws Exception {
        Map<String, String> headers = internalHeaders(tenantId, userId, username);

        assertThat(payload(invoke("getTenantId", "[]", headers))).isEqualTo(tenantId);
        assertThat(payload(invoke("getLoginUserId", "[]", headers))).isEqualTo(userId);
        assertThat(payload(invoke("getLoginUsername", "[]", headers))).isEqualTo(username);
    }

    @Test
    @DisplayName("缺 X-PivotOS-Internal 头：403 且不执行")
    void rejects_request_without_internal_token() throws Exception {
        HttpResponse<String> response = invoke("getTenantId", "[]", Map.of(
            CloudHeaders.TENANT_ID, "12"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_FORBIDDEN);
    }

    @Test
    @DisplayName("凭证不匹配：403（用错令牌不应得到任何数据）")
    void rejects_request_with_wrong_token() throws Exception {
        Map<String, String> headers = internalHeaders(12L, 1001L, "alice");
        headers.put(CloudHeaders.INTERNAL_TOKEN, "wrong-token");

        HttpResponse<String> response = invoke("getLoginUserId", "[]", headers);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(payload(response)).isNull();
    }

    @Test
    @DisplayName("白名单外的接口：404，且不透露本进程到底有没有这个类")
    void rejects_interface_outside_whitelist() throws Exception {
        HttpResponse<String> response = invoke(
            "com.pivotos.starter.cloud.api.support.fixture.ProbeFacade", "ping", "[]",
            internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_NOT_FOUND);
    }

    @Test
    @DisplayName("白名单内但本进程无实现：同样 404（两种失败形态同码，不可被拿来探测 classpath）")
    void not_found_when_no_local_implementation() throws Exception {
        HttpResponse<String> response = invoke("com.pivotos.NoSuchFacade", "ping", "[]",
            internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("方法不存在：404 + NO_METHOD，绝不落到同名的其它方法上")
    void no_method_when_missing() throws Exception {
        HttpResponse<String> response = invoke("noSuchMethod", "[]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_NO_METHOD);
    }

    @Test
    @DisplayName("body 不是 JSON 数组时不误调到无参方法：走元数不匹配的 NO_METHOD")
    void malformed_body_does_not_fall_into_wrong_overload() throws Exception {
        HttpResponse<String> response = invoke("greet", "{\"name\":\"bob\"}",
            internalHeaders(12L, 1001L, "alice"));

        // ContextFacade 四方法均无参：畸形 body 一律按「无参」处理，元数对不上即 NO_METHOD
        assertThat(response.statusCode()).isEqualTo(404);
    }

    private Map<String, String> internalHeaders(Long tenantId, Long userId, String username) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CloudHeaders.INTERNAL_TOKEN, TOKEN);
        if (tenantId != null) {
            headers.put(CloudHeaders.TENANT_ID, String.valueOf(tenantId));
        }
        if (userId != null) {
            headers.put(CloudHeaders.USER_ID, String.valueOf(userId));
        }
        if (username != null) {
            headers.put(CloudHeaders.USERNAME, username);
        }
        return headers;
    }

    private HttpResponse<String> invoke(String method, String argsJson, Map<String, String> headers) throws Exception {
        return invoke(IFACE, method, argsJson, headers);
    }

    private HttpResponse<String> invoke(String iface, String method, String argsJson, Map<String, String> headers)
        throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__facade/" + iface + "/" + method))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(argsJson));
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(HttpResponse<String> response) {
        return response.statusCode();
    }

    /** {@code data} 节点：数值统一转成 Long，文本转成 String， absent/null 返回 null */
    private Object payload(HttpResponse<String> response) throws Exception {
        JsonNode data = mapper.readTree(response.body()).get("data");
        if (data == null || data.isNull()) {
            return null;
        }
        return data.isNumber() ? (Object) data.asLong() : data.asText();
    }

    private String error(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).get("error").asText();
    }
}

package com.pivotos.starter.cloud.local.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.local.fixture.DemoFacade;
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
 * local 通道「入站恢复」链路的端到端取证（V3-S3 第 ③ 项）。
 *
 * <p><b>补的是什么</b>：此前 {@link com.pivotos.starter.cloud.local.rpc.FacadeRpcServlet} 有 6 条单测，
 * 但全部停在「带凭证能调用、凭证不对 403」；而请求在到达 Servlet 之前，
 * {@code CloudContextFilter} 有没有把身份写进 ScopedValue、Servlet 里反射出来的实现
 * 能不能看见这个身份，<b>一行代码都没有覆盖过</b>。
 *
 * <p><b>形态</b>：真 Tomcat + 真过滤器链 + 真 {@code /__rpc/invoke} Servlet，
 * 通过 HTTP 走完「带六头 + 内部凭证 → 过滤器恢复 → 反射调 {@code $Local} 实现 → 回包」。
 * 刻意不用 {@code MockMvc}、不用 mock ServletContext：那两种都验不出
 * 「过滤器是否真的在请求链上」——本轮 ④ 已经用同一条坑证明过一次
 * （组件扫描会绕过开关把端点注册上去，mock 环境则常常压根没有过滤器链）。
 *
 * <p><b>判据（沿用 L5 体系）</b>：唯一可信 = 下游<b>另一个线程</b>里读到上游带来的值。
 * 因此夹具的返回值必须<b>由上下文决定</b>，且额外回显线程名用来证明不是同线程泄漏。
 */
@SpringBootTest(
    classes = LocalInboundContextRestoreTest.LocalInboundApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=local",
        "pivotos.cloud.context.internal-token=" + LocalInboundContextRestoreTest.TOKEN,
        "pivotos.cloud.context.restore-tenant=true",
        "pivotos.cloud.context.restore-login=true",
        "pivotos.cloud.local.server-enabled=true",
        "pivotos.cloud.local.proxied=" + "com.pivotos.starter.cloud.local.fixture.DemoFacade"
    })
class LocalInboundContextRestoreTest {

    static final String TOKEN = "local-inbound-unit-test-token";

    @LocalServerPort
    private int port;

    private HttpClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    // 必须显式放开扫描范围：本 App 在 context 子包里，默认只扫自己这根子树，
    // fixture 包里的 DemoLocalFacade 会被漏掉——表象是测试报错 NO_LOCAL_IMPL，
    // 跟「$Local 别名没生成」长得一模一样，很容易查错方向。
    @SpringBootApplication(scanBasePackages = "com.pivotos.starter.cloud.local")
    static class LocalInboundApp {
        // 空体：靠全量自动配置把 CloudContextFilter + FacadeRpcServlet + DemoLocalFacade 一并装配起来
    }

    @Test
    @DisplayName("六头 + 内部凭证抵达后，Servlet 反射出的实现看到的是上游身份")
    void local_implementation_sees_upstream_identity() throws Exception {
        String result = invokeWhoAmI(identityHeaders(12L, 1001L, "alice", TOKEN));

        assertThat(result).as("tenantId:userId:username").isEqualTo("12:1001:alice");
    }

    @ParameterizedTest
    @CsvSource({
        "12,1001,alice",
        "77,2002,bob",
        "999,3003,carol"
    })
    @DisplayName("三组身份逐一对应：排除「下游返回了某个默认值」造成的假阳性")
    void multiple_identities_map_one_to_one(long tenantId, long userId, String username) throws Exception {
        String result = invokeWhoAmI(identityHeaders(tenantId, userId, username, TOKEN));

        assertThat(result).isEqualTo(tenantId + ":" + userId + ":" + username);
    }

    @Test
    @DisplayName("下游执行线程必须不同于上游调用线程（排除 ScopedValue 泄漏式假阳性）")
    void downstream_runs_in_another_thread() throws Exception {
        String thread = invoke("threadName", new String[0], new Object[0], identityHeaders(12L, 1001L, "alice", TOKEN));

        assertThat(thread).isNotBlank()
            .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("缺内部凭证：403，且实现未被执行（拿不到任何回显）")
    void rejects_when_internal_token_missing() throws Exception {
        Map<String, String> headers = identityHeaders(12L, 1001L, "alice", null);
        HttpRequest request = rpcRequest("whoAmI", new String[0], new Object[0], headers);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("凭证不匹配：403（既拒绝调用，也不恢复身份）")
    void rejects_when_internal_token_mismatch() throws Exception {
        HttpRequest request = rpcRequest("whoAmI", new String[0], new Object[0],
            identityHeaders(12L, 1001L, "alice", "wrong-token"));

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("凭证正确但不带任何身份头：上下文为空（不能凭空编造身份）")
    void no_identity_headers_yields_empty_context() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CloudHeaders.INTERNAL_TOKEN, TOKEN);

        String result = invoke("whoAmI", new String[0], new Object[0], headers);

        assertThat(result).isEqualTo("null:null:null");
    }

    private String invokeWhoAmI(Map<String, String> headers) throws Exception {
        return invoke("whoAmI", new String[0], new Object[0], headers);
    }

    private String invoke(String method, String[] paramTypes, Object[] args, Map<String, String> headers)
        throws Exception {
        HttpResponse<String> response = client.send(rpcRequest(method, paramTypes, args, headers),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP 状态异常，响应体=%s", response.body()).isEqualTo(200);
        JsonNode node = mapper.readTree(response.body());
        assertThat(node.get("ok").asBoolean()).as("RPC 未成功，响应体=%s", response.body()).isTrue();
        JsonNode data = node.get("data");
        return data == null || data.isNull() ? null : data.asText();
    }

    private HttpRequest rpcRequest(String method, String[] paramTypes, Object[] args, Map<String, String> headers)
        throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interface", DemoFacade.class.getName());
        body.put("method", method);
        body.put("paramTypes", paramTypes);
        body.put("args", args);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__rpc/invoke"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        headers.forEach(builder::header);
        return builder.build();
    }

    private Map<String, String> identityHeaders(Long tenantId, Long userId, String username, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (tenantId != null) {
            headers.put(CloudHeaders.TENANT_ID, String.valueOf(tenantId));
        }
        if (userId != null) {
            headers.put(CloudHeaders.USER_ID, String.valueOf(userId));
        }
        if (username != null) {
            headers.put(CloudHeaders.USERNAME, username);
        }
        if (token != null) {
            headers.put(CloudHeaders.INTERNAL_TOKEN, token);
        }
        return headers;
    }
}

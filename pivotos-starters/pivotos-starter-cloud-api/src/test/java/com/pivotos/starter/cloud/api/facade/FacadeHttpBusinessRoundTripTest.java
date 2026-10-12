package com.pivotos.starter.cloud.api.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.api.facade.fixture.BizLikeFacade;
import com.pivotos.starter.cloud.api.facade.fixture.BizLikeLocalFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务 Facade 形态的 HTTP 往返取证（V3-S4 P0/P1 关卡）。
 *
 * <p><b>验的不是「端点能返回东西」，而是五件会静默错的事</b>：
 * <ol>
 *   <li><b>D1 泛型实参</b>：{@code Collection<Long>} 的元素运行时类型必须是 {@code Long}。
 *       「返回 200」本身证明不了这一点（只有实现里真的按 Long 用才会炸），
 *       所以这里既跑 {@code listByIds} 也把元素类型直接回显出来断言；</li>
 *   <li><b>D2 时间/Optional</b>：{@code LocalDateTime} 必须是 ISO-8601 串、
 *       {@code Optional} 必须被摊平成值或 null —— 缺 jsr310/jdk8 模块时是 500 或畸形对象；</li>
 *   <li><b>D3 上下文</b>：业务方法读到的必须是<b>入站头恢复出来的身份</b>，
 *       且「带身份头」与「不带」结果必须不同（否则是假阳性）；</li>
 *   <li><b>异常映射</b>：业务异常要带回业务码与文案（200 + ok=false），
 *       非业务异常回 500 且<b>绝不回原文</b>；</li>
 *   <li><b>方法级白名单</b>：没列进白名单的写方法（本例的 {@code preview}）必须 404。</li>
 * </ol>
 *
 * <p><b>为什么刻意用 {@code @SpringBootApplication} 全量自动配置</b>：与
 * {@code FacadeHttpExposureTest} 同口径 —— 要验的就是「自动配置清单 + 过滤器顺序」这套组合拳，
 * 手列 {@code @ImportAutoConfiguration} 漏一条就会得到假的绿。
 */
@SpringBootTest(
    classes = FacadeHttpBusinessRoundTripTest.BizProbeApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.context.internal-token=" + FacadeHttpBusinessRoundTripTest.TOKEN,
        "pivotos.cloud.context.restore-tenant=true",
        "pivotos.cloud.context.restore-login=true",
        "pivotos.cloud.facade-http.enabled=true",
        // 方法级白名单：preview 刻意不列进去（它是写方法替身，验 P4 的「未配置即 404」）
        // ⚠️ 条目之间逗号、方法之间分号：逗号会被 Spring 切成独立条目（详见 FacadeHttpWhitelist）
        "pivotos.cloud.facade-http.interfaces="
            + "com.pivotos.starter.cloud.api.facade.fixture.BizLikeFacade:"
            + "listByIds;argElementType;stats;readFile;pageByUser;rerank;touch;reject;boom;"
            + "buildUrl;currentUserId;currentTenantId"
    })
class FacadeHttpBusinessRoundTripTest {

    static final String TOKEN = "biz-round-trip-token";

    private static final String IFACE = BizLikeFacade.class.getName();

    @LocalServerPort
    private int port;

    @Autowired
    private BizLikeLocalFacade impl;

    private HttpClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    @SpringBootApplication
    static class BizProbeApp {

        @Bean
        BizLikeLocalFacade bizLikeFacade() {
            return new BizLikeLocalFacade();
        }
    }

    // ==================== D1：泛型实参不得擦除 ====================

    @Test
    @DisplayName("Collection<Long> 的元素运行时类型必须是 Long（不是 Integer）——D1 的硬证据")
    void collection_long_argument_keeps_long_element_type() throws Exception {
        HttpResponse<String> response = invoke("argElementType", "[[1,2]]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(payload(response).asText()).isEqualTo("java.lang.Long");
    }

    @Test
    @DisplayName("批量查询：实现里按 Long 迭代不炸，且逐项对应")
    void list_by_ids_returns_dtos_in_order() throws Exception {
        HttpResponse<String> response = invoke("listByIds", "[[1,2]]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = payload(response);
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(1L);
        assertThat(data.get(1).get("id").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("单标量参数：单元素数组不得被摊平（摊平后参数个数变 0，静默退化成 NO_METHOD）")
    void single_scalar_argument_is_not_unwrapped() throws Exception {
        HttpResponse<String> response = invoke("buildUrl", "[\"abc\"]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(payload(response).asText()).isEqualTo("url:abc");
    }

    // ==================== D2：时间与 Optional 形态 ====================

    @Test
    @DisplayName("LocalDateTime 序列化为 ISO-8601 串（缺席 jsr310 模块时会 500 或退化成嵌套对象）")
    void local_date_time_serializes_as_iso_string() throws Exception {
        HttpResponse<String> response = invoke("stats", "[]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(payload(response).get("createTime").asText()).isEqualTo("2026-10-10T12:30:45");
    }

    @Test
    @DisplayName("Optional 有值时摊平为值，空值时为 null（缺席 jdk8 模块时会变成 {present:...}）")
    void optional_flattens_to_value_or_null() throws Exception {
        HttpResponse<String> present = invoke("readFile", "[\"repo\",\"a/b\"]", internalHeaders(12L, 1001L, "alice"));
        HttpResponse<String> empty = invoke("readFile", "[\"repo\",\"missing\"]", internalHeaders(12L, 1001L, "alice"));

        assertThat(payload(present).asText()).isEqualTo("content-of-repo/a/b");
        assertThat(payload(empty)).isNull();
    }

    // ==================== 分页 / void / record ====================

    @Test
    @DisplayName("PageResult<T> 与嵌套查询对象完整往返")
    void page_result_and_nested_query_round_trip() throws Exception {
        HttpResponse<String> response = invoke("pageByUser", "[1,{\"readStatus\":0}]",
            internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = payload(response);
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        assertThat(data.get("list")).hasSize(1);
        // name 里回显了查询对象的 readStatus：证明嵌套对象真的被反序列化
        assertThat(data.get("list").get(0).get("name").asText()).isEqualTo("msg-for-1-rs0");
    }

    @Test
    @DisplayName("void 方法：ok=true + data=null，且方法确实被执行了")
    void void_method_returns_null_data_but_really_executes() throws Exception {
        int before = impl.touchCount();
        HttpResponse<String> response = invoke("touch", "[]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(payload(response)).isNull();
        assertThat(impl.touchCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("List<String> 入参 + record 返回值")
    void list_string_argument_and_record_return() throws Exception {
        HttpResponse<String> response = invoke("rerank", "[\"q\",[\"a\",\"b\"],2]",
            internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = payload(response);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("index").asInt()).isEqualTo(0);
        assertThat(data.get(0).get("score").asDouble()).isEqualTo(0.9d);
    }

    // ==================== 异常映射 ====================

    @Test
    @DisplayName("业务异常：HTTP 200 + ok=false + 业务码与文案（与 local 通道同口径）")
    void service_exception_carries_business_code_and_message() throws Exception {
        HttpResponse<String> response = invoke("reject", "[\"非本人待办\"]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("error").asText()).isEqualTo(FacadeHttpConstants.ERROR_SERVICE);
        assertThat(body.get("code").asInt()).isEqualTo(1500);
        assertThat(body.get("message").asText()).isEqualTo("非本人待办");
    }

    @Test
    @DisplayName("非业务异常：HTTP 500 + INVOCATION_FAILED，且绝不回传异常原文")
    void unexpected_exception_does_not_leak_internal_message() throws Exception {
        HttpResponse<String> response = invoke("boom", "[]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_INVOCATION);
        assertThat(response.body()).doesNotContain("secret-internal-detail-must-not-leak");
    }

    @Test
    @DisplayName("参数形态错：400 BAD_ARGS，不打成 500（500 会诱导调用方无意义重试）")
    void bad_argument_shape_returns_400() throws Exception {
        HttpResponse<String> response = invoke("argElementType", "[\"not-a-number\"]",
            internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_BAD_ARGS);
    }

    // ==================== 方法级白名单（P4 负向） ====================

    @Test
    @DisplayName("接口在白名单但方法不在：404（写方法不配置就调不到）")
    void method_outside_whitelist_returns_404() throws Exception {
        HttpResponse<String> response = invoke("preview", "[{\"a\":1}]", internalHeaders(12L, 1001L, "alice"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response)).isEqualTo(FacadeHttpConstants.ERROR_NOT_FOUND);
    }

    // ==================== D3：业务方法看到的是入站恢复后的上下文 ====================

    @ParameterizedTest
    @CsvSource({
        "12,1001,alice",
        "77,2002,bob",
        "999,3003,carol"
    })
    @DisplayName("三组身份逐一对应：排除「下游返回了某个默认值」的假阳性")
    void business_method_sees_identity_restored_from_inbound(long tenantId, long userId, String username)
        throws Exception {
        Map<String, String> headers = internalHeaders(tenantId, userId, username);

        assertThat(payload(invoke("currentTenantId", "[]", headers)).asLong()).isEqualTo(tenantId);
        assertThat(payload(invoke("currentUserId", "[]", headers)).asLong()).isEqualTo(userId);
    }

    @Test
    @DisplayName("不带身份头时必须读到 null：证明「有值」来自入站头而不是进程残留")
    void business_method_sees_null_when_no_identity_headers() throws Exception {
        HttpResponse<String> tenant = invoke("currentTenantId", "[]", Map.of(CloudHeaders.INTERNAL_TOKEN, TOKEN));
        HttpResponse<String> userId = invoke("currentUserId", "[]", Map.of(CloudHeaders.INTERNAL_TOKEN, TOKEN));

        assertThat(tenant.statusCode()).isEqualTo(200);
        assertThat(userId.statusCode()).isEqualTo(200);
        assertThat(payload(tenant)).isNull();
        assertThat(payload(userId)).isNull();
    }

    // ==================== 工具 ====================

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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__facade/" + IFACE + "/" + method))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(argsJson));
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code data} 节点；absent/null 返回 null */
    private JsonNode payload(HttpResponse<String> response) throws Exception {
        JsonNode data = mapper.readTree(response.body()).get("data");
        return data == null || data.isNull() ? null : data;
    }

    private String error(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body()).get("error").asText();
    }
}

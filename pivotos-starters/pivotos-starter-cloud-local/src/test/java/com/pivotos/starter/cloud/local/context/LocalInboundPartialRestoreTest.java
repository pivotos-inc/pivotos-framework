package com.pivotos.starter.cloud.local.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.local.fixture.DemoFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 入站恢复的<b>颗粒度</b>取证：两个开关是独立的，开租户不等于开身份。
 *
 * <p>这是安全口径最容易被糊弄的一处——「恢复了点什么」被当成「恢复成功」。
 * 这里把 restore-login 单独关掉，要求用户 id/username 必须是 null（而不是顺手带上），
 * 以此钉死「部分恢复」的确切边界。
 */
@SpringBootTest(
    classes = LocalInboundPartialRestoreTest.PartialRestoreApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.banner-mode=off",
        "pivotos.cloud.enabled=true",
        "pivotos.cloud.provider=local",
        "pivotos.cloud.context.internal-token=" + LocalInboundPartialRestoreTest.TOKEN,
        "pivotos.cloud.context.restore-tenant=true",
        "pivotos.cloud.context.restore-login=false",
        "pivotos.cloud.local.server-enabled=true",
        "pivotos.cloud.local.proxied=com.pivotos.starter.cloud.local.fixture.DemoFacade"
    })
class LocalInboundPartialRestoreTest {

    static final String TOKEN = "local-partial-unit-test-token";

    @LocalServerPort
    private int port;

    private HttpClient client;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().build();
        mapper = new ObjectMapper();
    }

    // 同上：显式放开扫描范围，否则拿不到 fixture 包里的 DemoLocalFacade
    @SpringBootApplication(scanBasePackages = "com.pivotos.starter.cloud.local")
    static class PartialRestoreApp {
    }

    @Test
    @DisplayName("只开 restore-tenant：租户恢复、身份必须是 null（不许顺手带上）")
    void tenant_only_restore_leaves_identity_empty() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CloudHeaders.INTERNAL_TOKEN, TOKEN);
        headers.put(CloudHeaders.TENANT_ID, "12");
        headers.put(CloudHeaders.USER_ID, "1001");
        headers.put(CloudHeaders.USERNAME, "alice");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interface", DemoFacade.class.getName());
        body.put("method", "whoAmI");
        body.put("paramTypes", new String[0]);
        body.put("args", new Object[0]);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__rpc/invoke"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .header(CloudHeaders.INTERNAL_TOKEN, TOKEN)
            .header(CloudHeaders.TENANT_ID, "12")
            .header(CloudHeaders.USER_ID, "1001")
            .header(CloudHeaders.USERNAME, "alice")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).get("data");
        assertThat(data.asText())
            .as("restore-login=false 时身份必须是 null，否则等于绕过登录态恢复开关")
            .isEqualTo("12:null:null");
    }
}

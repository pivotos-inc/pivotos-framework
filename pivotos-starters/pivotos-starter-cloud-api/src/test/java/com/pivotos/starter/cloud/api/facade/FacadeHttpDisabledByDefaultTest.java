package com.pivotos.starter.cloud.api.facade;

import com.pivotos.common.api.context.ContextFacade;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认形态必须零行为：没显式开关时，端口上不许出现 {@code /__facade/**}。
 *
 * <p>这是 V3 一票否决点「引入 Starter 但保持零行为」的边界守卫——
 * 反射端点一旦误配为默认开启，单体形态就会凭空多出一个可按 URL 调 Bean 的入口。
 * 配错/漏配要能被这条测试当场抓住，而不是等上线后被扫端口发现。
 */
@SpringBootTest(
    classes = FacadeHttpDisabledByDefaultTest.FacadeHttpDefaultApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.banner-mode=off",
        // 刻意只给凭证、不给开关：凭证也不是「顺便开端点」的理由
        "pivotos.cloud.context.internal-token=facade-http-unit-test-token"
    })
class FacadeHttpDisabledByDefaultTest {

    @LocalServerPort
    private int port;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().build();
    }

    @SpringBootApplication
    static class FacadeHttpDefaultApp {
        // 空体：跑的就是默认形态
    }

    @Test
    @DisplayName("未开启开关：端点不存在（既非 200，也不是 403——压根没注册）")
    void endpoint_is_not_registered_when_disabled() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__facade/" + ContextFacade.class.getName() + "/getTenantId"))
            .header("Content-Type", "application/json")
            .header(CloudHeaders.INTERNAL_TOKEN, "facade-http-unit-test-token")
            .POST(HttpRequest.BodyPublishers.ofString("[]"))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
            .as("默认形态下 /__facade/** 必须是 404（未注册），403 则说明过滤链认了这个路径、" +
                "200 则说明端点穿上去了——两者都属失败")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("即便带全套身份头也打不通：返回值不得含 ok=true 的响应体")
    void no_payload_even_with_full_identity_headers() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/__facade/" + ContextFacade.class.getName() + "/getLoginUserId"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("[]"))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("\"ok\":true");
    }
}

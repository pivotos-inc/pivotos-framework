package com.pivotos.starter.job.client;

import com.pivotos.starter.job.config.JobProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link XxlJobAdminClient} 单元测试。
 * <p>
 * 使用 JDK 内置 {@code com.sun.net.httpserver.HttpServer} 模拟 XXL-Job admin Open API 响应，
 * 覆盖 addJob/updateJob/removeJob/startJob/stopJob/triggerJob/pageListJob 正常路径与网络异常降级。
 *
 * @author PivotOS
 * @since 2.10.0
 */
@DisplayName("XxlJobAdminClient tests")
class XxlJobAdminClientTest {

    private HttpServer server;
    private XxlJobAdminClient client;
    private String responseBody;
    private int responseCode;

    @BeforeEach
    void setUp() throws IOException {
        // 启动本地 mock HTTP 服务器
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", new MockHandler());
        server.start();

        InetSocketAddress address = server.getAddress();
        String baseUrl = "http://" + address.getHostString() + ":" + address.getPort();

        JobProperties props = new JobProperties();
        props.setAdminAddresses(baseUrl);
        props.setAccessToken("test-token");
        props.setAppName("test-executor");
        props.setGroupId(1);
        client = new XxlJobAdminClient(props);

        // 默认成功响应
        responseBody = "{\"code\":200}";
        responseCode = 200;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("addJob 成功返回 jobId")
    void addJob_success() {
        responseBody = "{\"code\":200,\"data\":42}";
        XxlJobResult result = client.addJob(
                "测试任务", "testHandler", "param",
                "CRON", "0 * * * * ?",
                "FIRE_ONCE_NOW", "ROUND", "SERIAL_EXECUTION",
                0, 0);
        assertThat(result.success()).isTrue();
        assertThat(result.jobId()).isEqualTo(42);
    }

    @Test
    @DisplayName("updateJob 成功")
    void updateJob_success() {
        XxlJobResult result = client.updateJob(
                10, "测试任务", "testHandler", "param",
                "CRON", "0 * * * * ?",
                "FIRE_ONCE_NOW", "ROUND", "SERIAL_EXECUTION",
                0, 0);
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("removeJob 成功")
    void removeJob_success() {
        XxlJobResult result = client.removeJob(10);
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("startJob 成功")
    void startJob_success() {
        XxlJobResult result = client.startJob(10);
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("stopJob 成功")
    void stopJob_success() {
        XxlJobResult result = client.stopJob(10);
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("triggerJob 成功")
    void triggerJob_success() {
        XxlJobResult result = client.triggerJob(10, "execParam");
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("addJob 返回错误码时 fail 并携带 msg")
    void addJob_errorResponse() {
        responseBody = "{\"code\":500,\"msg\":\"任务名重复\"}";
        XxlJobResult result = client.addJob(
                "重复任务", "testHandler", "",
                "CRON", "0 * * * * ?",
                "FIRE_ONCE_NOW", "ROUND", "SERIAL_EXECUTION",
                0, 0);
        assertThat(result.success()).isFalse();
        assertThat(result.msg()).contains("任务名重复");
    }

    @Test
    @DisplayName("网络不可达时降级为 fail 不抛异常")
    void networkFailure_degradesGracefully() {
        // 指向一个不存在的端口
        JobProperties props = new JobProperties();
        props.setAdminAddresses("http://127.0.0.1:1");
        props.setAccessToken("test-token");
        props.setAppName("test-executor");
        props.setGroupId(1);
        XxlJobAdminClient unreachableClient = new XxlJobAdminClient(props);

        XxlJobResult result = unreachableClient.addJob(
                "测试任务", "testHandler", "",
                "CRON", "0 * * * * ?",
                "FIRE_ONCE_NOW", "ROUND", "SERIAL_EXECUTION",
                0, 0);
        assertThat(result.success()).isFalse();
        // ConnectException.getMessage() 可能为 null，只要 success=false 且不抛异常即通过
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("pageListJob 成功返回任务列表")
    void pageListJob_success() {
        responseBody = "{\"code\":200,\"data\":{\"data\":[" +
                "{\"id\":1,\"jobDesc\":\"测试任务\",\"executorHandler\":\"testHandler\",\"triggerStatus\":1,\"triggerNextTime\":1700000000000,\"triggerLastTime\":1699999999000}" +
                "],\"total\":1}}";
        XxlJobPageResult result = client.pageListJob(0, 10, "", -1);
        assertThat(result.success()).isTrue();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).get("executorHandler").getAsString()).isEqualTo("testHandler");
    }

    @Test
    @DisplayName("pageListJob 空列表")
    void pageListJob_emptyResult() {
        responseBody = "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}";
        XxlJobPageResult result = client.pageListJob(0, 10, "", -1);
        assertThat(result.success()).isTrue();
        assertThat(result.total()).isEqualTo(0);
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("pageListJob 网络不可达时降级为 fail 不抛异常")
    void pageListJob_networkFailure() {
        JobProperties props = new JobProperties();
        props.setAdminAddresses("http://127.0.0.1:1");
        props.setAccessToken("test-token");
        props.setAppName("test-executor");
        props.setGroupId(1);
        XxlJobAdminClient unreachableClient = new XxlJobAdminClient(props);

        XxlJobPageResult result = unreachableClient.pageListJob(0, 10, "", -1);
        assertThat(result.success()).isFalse();
    }

    // ---------- mock HTTP handler ----------

    private class MockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(responseCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}

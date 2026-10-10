package com.pivotos.starter.cloud.local.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.local.fixture.DemoFacade;
import com.pivotos.starter.cloud.local.fixture.DemoLocalFacade;
import com.pivotos.starter.cloud.local.rpc.FacadeRpcProxyFactoryBean.RpcResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自研 RPC 服务端测试：用内存 Servlet 容器跑完「鉴权 → 定位 $Local → 反射调用 → 回包」整条链路。
 *
 * <p>重点验的是安全口径：<b>没配 internal-token 时端点必须 403</b>。
 * 这个端点能反射调任意 Facade 方法，敞开等于后门。
 */
class FacadeRpcServletTest {

    private static final String TOKEN = "unit-test-token";

    private ObjectMapper objectMapper;
    private MockServletContext servletContext;
    private FacadeRpcServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        servletContext = new MockServletContext();

        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.setServletContext(servletContext);
        // 以 $Local 别名注册：模拟 FacadeRpcRegistrar 替换后「本地实现」的存在形态
        context.registerBean("demoFacade$Local", DemoLocalFacade.class);
        context.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);

        servlet = new FacadeRpcServlet(cloudProperties(TOKEN));
        servlet.init(new MockServletConfig(servletContext));
    }

    @Test
    void rejects_when_internal_token_not_configured() throws Exception {
        FacadeRpcServlet noTokenServlet = new FacadeRpcServlet(cloudProperties(""));
        noTokenServlet.init(new MockServletConfig(servletContext));
        MockHttpServletResponse response = new MockHttpServletResponse();
        noTokenServlet.doPost(rpcRequest("greet", new String[]{"java.lang.String"}, new String[]{"bob"}, TOKEN), response);
        assertThat(response.getStatus()).as("未配凭证时必须 403，绝不能放行").isEqualTo(403);
    }

    @Test
    void rejects_when_token_mismatch() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(rpcRequest("greet", new String[]{"java.lang.String"}, new String[]{"bob"}, "wrong-token"), response);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void invokes_local_implementation_and_returns_value() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(rpcRequest("greet", new String[]{"java.lang.String"}, new String[]{"bob"}, TOKEN), response);

        assertThat(response.getStatus()).isEqualTo(200);
        RpcResponse parsed = objectMapper.readValue(response.getContentAsString(), RpcResponse.class);
        assertThat(parsed.isOk()).isTrue();
        assertThat(String.valueOf(parsed.getData())).isEqualTo("hello,bob");
    }

    @Test
    void returns_failure_body_for_unknown_method() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(rpcRequest("notExist", new String[0], new String[0], TOKEN), response);
        RpcResponse parsed = objectMapper.readValue(response.getContentAsString(), RpcResponse.class);
        assertThat(parsed.isOk()).isFalse();
        assertThat(parsed.getError()).startsWith("NO_METHOD");
    }

    @Test
    void returns_failure_body_for_unknown_interface() throws Exception {
        MockHttpServletRequest request = baseRequest(TOKEN);
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "interface", "com.pivotos.starter.cloud.local.fixture.NoSuchFacade",
            "method", "greet",
            "paramTypes", new String[0],
            "args", new Object[0]));
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        JsonNode node = objectMapper.readTree(response.getContentAsString());
        assertThat(node.get("ok").asBoolean()).isFalse();
        assertThat(node.get("error").asText()).startsWith("NO_LOCAL_IMPL");
    }

    @Test
    void get_is_not_allowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(new MockHttpServletRequest(), response);
        assertThat(response.getStatus()).isEqualTo(405);
    }

    private MockHttpServletRequest rpcRequest(String method, String[] paramTypes, Object[] args, String token) throws Exception {
        MockHttpServletRequest request = baseRequest(token);
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "interface", DemoFacade.class.getName(),
            "method", method,
            "paramTypes", paramTypes,
            "args", args));
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest baseRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(FacadeRpcConstants.ENDPOINT);
        if (token != null) {
            request.addHeader(CloudHeaders.INTERNAL_TOKEN, token);
        }
        return request;
    }

    private static CloudProperties cloudProperties(String token) {
        CloudProperties properties = new CloudProperties();
        properties.getContext().setInternalToken(token);
        return properties;
    }
}

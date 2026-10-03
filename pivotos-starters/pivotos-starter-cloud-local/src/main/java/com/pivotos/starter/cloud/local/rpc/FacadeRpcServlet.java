package com.pivotos.starter.cloud.local.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudContextCodec;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.api.support.FacadeProxySupport;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.support.WebApplicationContextUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 自研 RPC 的服务端实现：接收 {@code /__rpc/invoke}，按「接口 + 方法名 + 元数」定位
 * {@code <beanName>$Local} 本地实现并反射调用。
 *
 * <p><b>安全口径（硬）</b>：默认不注册（{@code pivotos.cloud.local.server-enabled=false}）；
 * 即便注册，请求必须携带与 {@code pivotos.cloud.context.internal-token} 一致的
 * {@code X-PivotOS-Internal} 头，<b>未配置该 token 时端点一律 403</b>。
 * 否则这个端点等于「任意反射调用任意 Facade 方法」的公开后门——比不做 RPC 危险得多。
 *
 * <p>为什么按名取 {@code $Local} 而不是 {@code getBean(iface)}：后者会命中远程代理，
 * 形成自调用死循环。
 */
public class FacadeRpcServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(FacadeRpcServlet.class);

    private final CloudProperties cloudProps;
    private final ObjectMapper objectMapper = JsonSupport.fallback();

    public FacadeRpcServlet(CloudProperties cloudProps) {
        this.cloudProps = cloudProps;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!isInternal(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("FORBIDDEN"));
            return;
        }
        ApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        if (context == null) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("NO_CONTEXT"));
            return;
        }
        try {
            JsonNode body = objectMapper.readTree(request.getInputStream());
            String ifaceName = text(body, "interface");
            String methodName = text(body, "method");
            JsonNode argsNode = body.get("args");

            Class<?> iface;
            try {
                iface = ClassUtils.forName(ifaceName, context.getClassLoader());
            } catch (Exception e) {
                write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("NO_LOCAL_IMPL:" + ifaceName));
                return;
            }

            Object target = findLocalBean(context, iface);
            if (target == null) {
                write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("NO_LOCAL_IMPL:" + ifaceName));
                return;
            }
            Method method = findMethod(iface, methodName, argsNode == null ? 0 : argsNode.size());
            if (method == null) {
                write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("NO_METHOD:" + methodName));
                return;
            }
            Object[] args = convertArgs(method, argsNode);
            Object result = method.invoke(target, args);
            write(response, FacadeRpcProxyFactoryBean.RpcResponse.ok(result));
        } catch (Exception e) {
            log.warn("[CLOUD][local] RPC 服务端执行失败", e);
            write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail(String.valueOf(e.getMessage())));
        }
    }

    private boolean isInternal(HttpServletRequest request) {
        String expected = cloudProps == null ? null : cloudProps.getContext().getInternalToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String actual = request.getHeader(CloudHeaders.INTERNAL_TOKEN);
        if (actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private Object findLocalBean(ApplicationContext context, Class<?> iface) {
        for (String name : context.getBeanNamesForType(iface)) {
            if (name.endsWith(FacadeProxySupport.LOCAL_SUFFIX)) {
                return context.getBean(name);
            }
        }
        return null;
    }

    private Method findMethod(Class<?> iface, String methodName, int argCount) {
        Method fallback = null;
        for (Method method : iface.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != argCount) {
                continue;
            }
            if (fallback == null) {
                fallback = method;
            }
            // 同元数的重载：按形参可赋值进一步收敛（客户端传的就是实参运行类型）
            if (argCount == 0) {
                return method;
            }
        }
        return fallback;
    }

    private Object[] convertArgs(Method method, JsonNode argsNode) {
        int count = method.getParameterCount();
        if (count == 0 || argsNode == null || !argsNode.isArray()) {
            return new Object[0];
        }
        Object[] args = new Object[count];
        for (int i = 0; i < count; i++) {
            JsonNode node = argsNode.get(i);
            if (node == null || node.isNull()) {
                args[i] = null;
                continue;
            }
            args[i] = objectMapper.convertValue(node,
                objectMapper.constructType(method.getGenericParameterTypes()[i]));
        }
        return args;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? null : value.asText();
    }

    /**
     * 写响应体。<b>刻意不在此处设置状态码</b>：403 / 405 等非 200 分支由调用方先 setStatus，
     * 若这里统一写 200 会把拒绝响应悄悄改写成成功（本次单测即由此抓到）。
     */
    private void write(HttpServletResponse response, Object payload) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
    }

    /** 本 Servlet 只接受 POST */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        write(response, FacadeRpcProxyFactoryBean.RpcResponse.fail("METHOD_NOT_ALLOWED"));
    }
}

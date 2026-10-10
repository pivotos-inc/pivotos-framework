package com.pivotos.starter.cloud.local.rpc;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudContextCodec;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.cloud.api.discovery.ServiceInstance;
import com.pivotos.starter.cloud.api.discovery.ServiceInstanceProvider;
import com.pivotos.starter.cloud.local.config.LocalCloudProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facade 远程代理：把「进程内 Bean 调用」替换成「HTTP JSON-RPC 调用」，
 * 调用方代码（乃至编译产物）零改动——这是 V2 一票否决点「业务代码零 diff」的实现点。
 *
 * <p><b>为什么依赖走 {@link BeanFactoryAware} 延迟取</b>：本 FactoryBean 的定义是在
 * {@code BeanDefinitionRegistryPostProcessor} 阶段注册的，那时容器还没完成刷新，
 * 拿 {@code ServiceInstanceProvider} 会造成过早实例化（并可能拉起注册中心连接）。
 * 因此这里只持有配置，真正的协作者在 {@code invoke} 时才从容器取。
 *
 * <p>出站上下文传播在此调用 {@link CloudContextCodec#capture()}——
 * 三个通道唯一的传播实现点，服务端由 {@link com.pivotos.starter.cloud.api.context.CloudContextFilter} 恢复。
 */
public class FacadeRpcProxyFactoryBean implements FactoryBean<Object>, InvocationHandler, BeanFactoryAware {

    private final Class<?> iface;
    private final LocalCloudProperties localProps;
    private final CloudProperties cloudProps;
    private final HttpClient httpClient;

    private BeanFactory beanFactory;
    private volatile Object proxy;

    public FacadeRpcProxyFactoryBean(Class<?> iface, LocalCloudProperties localProps, CloudProperties cloudProps) {
        this.iface = iface;
        this.localProps = localProps;
        this.cloudProps = cloudProps;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000L, localProps.getTimeoutMs())))
            .build();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object getObject() {
        if (proxy == null) {
            synchronized (this) {
                if (proxy == null) {
                    proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, this);
                }
            }
        }
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return iface;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> iface.getName() + "@rpc";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(this, args);
            };
        }
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null) {
            throw new IllegalStateException("[CLOUD][local] 未解析到目标地址：" + iface.getName()
                + "（请配 pivotos.cloud.local.base-url 或 instances." + localProps.getServiceId() + "）");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interface", iface.getName());
        body.put("method", method.getName());
        body.put("paramTypes", paramTypeNames(method));
        body.put("args", args == null ? new Object[0] : args);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + FacadeRpcConstants.ENDPOINT))
            .timeout(Duration.ofMillis(Math.max(1000L, localProps.getTimeoutMs())))
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper().writeValueAsString(body), StandardCharsets.UTF_8));

        if (cloudProps != null && cloudProps.getContext().isPropagate()) {
            for (Map.Entry<String, String> header : CloudContextCodec.toHeaders(CloudContextCodec.capture()).entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        String internalToken = cloudProps == null ? null : cloudProps.getContext().getInternalToken();
        if (internalToken != null && !internalToken.isBlank()) {
            builder.header(CloudHeaders.INTERNAL_TOKEN, internalToken);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("[CLOUD][local] RPC 调用失败：HTTP " + response.statusCode()
                + " " + iface.getSimpleName() + "#" + method.getName());
        }
        RpcResponse rpc = objectMapper().readValue(response.body(), RpcResponse.class);
        if (!rpc.isOk()) {
            throw new IllegalStateException("[CLOUD][local] 远端执行失败：" + rpc.getError());
        }
        if (method.getReturnType() == void.class || rpc.getData() == null) {
            return null;
        }
        JavaType javaType = objectMapper().getTypeFactory().constructType(method.getGenericReturnType());
        return objectMapper().readValue(objectMapper().writeValueAsString(rpc.getData()), javaType);
    }

    private String resolveBaseUrl() {
        if (localProps.getBaseUrl() != null && !localProps.getBaseUrl().isBlank()) {
            return localProps.getBaseUrl().trim();
        }
        ServiceInstance instance = instanceProvider() == null ? null : instanceProvider().first(localProps.getServiceId());
        return instance == null ? null : instance.baseUrl();
    }

    private ServiceInstanceProvider instanceProvider() {
        return beanFactory == null ? null : beanFactory.getBeanProvider(ServiceInstanceProvider.class).getIfAvailable();
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = beanFactory == null ? null : beanFactory.getBeanProvider(ObjectMapper.class).getIfAvailable();
        return mapper != null ? mapper : JsonSupport.fallback();
    }

    private static String[] paramTypeNames(Method method) {
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].getName();
        }
        return names;
    }

    /** RPC 响应体（与 {@code FacadeRpcServlet} 成对） */
    public static class RpcResponse {

        private boolean ok;
        private Object data;
        private String error;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        /** 成功响应 */
        public static RpcResponse ok(Object data) {
            RpcResponse response = new RpcResponse();
            response.setOk(true);
            response.setData(data);
            return response;
        }

        /** 失败响应（HTTP 仍为 200，错误信息放 body，便于客户端直接抛出带上下文的异常） */
        public static RpcResponse fail(String error) {
            RpcResponse response = new RpcResponse();
            response.setOk(false);
            response.setError(error);
            return response;
        }
    }
}

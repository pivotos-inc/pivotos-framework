package com.pivotos.cloud.openfeign;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S133 V3 spike —— Cloud Starter ①：通信抽象（语义对齐 Spring Cloud OpenFeign，自研通道实现）。
 *
 * <p><b>为何不用真实 openfeign jar</b>：本机运行时为 Spring Boot 4.1.0，与之配套的
 * Spring Cloud 2025.1.x 当时仅到 5.1.0-M1（Milestone，非 GA），spike 期引入平添「版本兼容」
 * 这一与结论无关的噪声变量。此处以 <b>JDK HttpClient + Jackson</b> 实现等价的
 * 「接口 → HTTP」通道，<b>业务侧代码改动为 0</b>；两者差异见《S133-收口报告.md》§4。
 *
 * <p><b>裁剪性</b>：纯 classpath 插件，开关为 {@code pivotos.cloud.openfeign.enabled}
 * （踩坑 11：不用 {@code @ConditionalOnBean}，避免时序窗口）。不引入即零行为。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.cloud.openfeign", name = "enabled", havingValue = "true")
public class CloudOpenFeignAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CloudOpenFeignAutoConfiguration.class);

    /** 通信抽象配置 */
    @ConfigurationProperties(prefix = "pivotos.cloud.openfeign")
    public static class FeignProps {
        /** 是否启用本 Starter（同时是自动配置的开关） */
        private boolean enabled = false;
        /** 是否在本进程暴露 RPC 服务端点（单体自闭环验证时需要） */
        private boolean serverEnabled = false;
        /** 显式服务地址（provider=static 时使用） */
        private String baseUrl = "http://127.0.0.1:8080";
        /** 需要改成远程调用的 Facade 接口全限定名，逗号分隔；空 = 只装配不代理 */
        private String proxied = "";
        /** 调用超时（毫秒） */
        private long timeoutMs = 8000;
        /** HMAC 签名开关 */
        private boolean hmacEnabled = false;
        /** HMAC 密钥；hmacEnabled=true 而密钥为空 → fail-fast */
        private String hmacSecret = "";
        /** 服务实例解析器：static（默认）/ nacos（cloud-nacos Starter 提供同名实现） */
        private String serviceInstanceProvider = "static";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isServerEnabled() {
            return serverEnabled;
        }

        public void setServerEnabled(boolean serverEnabled) {
            this.serverEnabled = serverEnabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getProxied() {
            return proxied;
        }

        public void setProxied(String proxied) {
            this.proxied = proxied;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isHmacEnabled() {
            return hmacEnabled;
        }

        public void setHmacEnabled(boolean hmacEnabled) {
            this.hmacEnabled = hmacEnabled;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public String getServiceInstanceProvider() {
            return serviceInstanceProvider;
        }

        public void setServiceInstanceProvider(String serviceInstanceProvider) {
            this.serviceInstanceProvider = serviceInstanceProvider;
        }

        /** 逗号分隔 → 接口全限定名集合 */
        public Set<String> proxiedInterfaces() {
            Set<String> set = new LinkedHashSet<>();
            for (String item : proxied.split(",")) {
                if (StringUtils.hasText(item)) {
                    set.add(item.trim());
                }
            }
            return set;
        }
    }

    /** 服务实例解析 SPI：nacos / static / 未来 k8s 等实现按此扩展，openfeign 侧零感知 */
    public interface ServiceInstanceProvider {
        String id();

        /** 返回形如 http://host:port 的服务地址 */
        String resolve(String serviceName);
    }

    /** 默认实现：静态地址，保证「不引入注册中心也能跑」 */
    public static class StaticServiceInstanceProvider implements ServiceInstanceProvider {
        private final FeignProps props;

        public StaticServiceInstanceProvider(FeignProps props) {
            this.props = props;
        }

        @Override
        public String id() {
            return "static";
        }

        @Override
        public String resolve(String serviceName) {
            return props.getBaseUrl();
        }
    }

    /** 分布式事务上下文（供 cloud-seata Starter 使用；未引入 seata 时为空运转） */
    public static final class TxContext {
        private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

        private TxContext() {
        }

        public static String currentId() {
            return CURRENT.get();
        }

        public static void begin(String id) {
            CURRENT.set(id);
        }

        public static void clear() {
            CURRENT.remove();
        }
    }

    /** 代理登记表：记录「Facade 接口 → 本地实现 bean 别名」，供服务端点精确取到本地 bean（避免自调用环） */
    public static final class RemoteFacadeRegistry {
        private static final Map<String, String> LOCAL_ALIAS = new ConcurrentHashMap<>();

        private RemoteFacadeRegistry() {
        }

        public static void put(String ifaceName, String localAlias) {
            LOCAL_ALIAS.put(ifaceName, localAlias);
        }

        public static String localAlias(String ifaceName) {
            return LOCAL_ALIAS.get(ifaceName);
        }
    }

    /** HMAC-SHA256 签名工具 */
    static final class Signer {
        static String sign(String body, String secret) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        static boolean valid(String body, String sign, String secret) {
            if (sign == null || secret == null || secret.isEmpty()) {
                return false;
            }
            try {
                return MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                        sign(body, secret).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * 显式从 Environment 绑定（<b>踩坑记录</b>：本机实测 Spring Boot 4.1.0 下，
     * 命令行参数能被 {@code @ConditionalOnProperty} 读到，但 {@code @ConfigurationProperties}
     * 的 binder 未把同一批属性绑进 Bean（boot 绑定链路待正式落地时另行排查）。
     * spike 期采取「Env 直读」以隔离无关变量，语义等价）。
     */
    @Bean
    public FeignProps feignProps(org.springframework.core.env.Environment env) {
        FeignProps p = new FeignProps();
        String prefix = "pivotos.cloud.openfeign.";
        p.setEnabled(env.getProperty(prefix + "enabled", Boolean.class, false));
        p.setServerEnabled(env.getProperty(prefix + "server-enabled", Boolean.class, false));
        p.setBaseUrl(env.getProperty(prefix + "base-url", "http://127.0.0.1:8080"));
        p.setProxied(env.getProperty(prefix + "proxied", ""));
        p.setTimeoutMs(env.getProperty(prefix + "timeout-ms", Long.class, 8000L));
        p.setHmacEnabled(env.getProperty(prefix + "hmac-enabled", Boolean.class, false));
        p.setHmacSecret(env.getProperty(prefix + "hmac-secret", ""));
        p.setServiceInstanceProvider(env.getProperty(prefix + "service-instance-provider", "static"));
        return p;
    }

    @Bean
    public ServiceInstanceProvider staticServiceInstanceProvider(FeignProps props) {
        return new StaticServiceInstanceProvider(props);
    }

    /** fail-fast（V3 否决点第三款）：HMAC 开启却没有密钥 → 起不来，而不是「带裸调用上线」 */
    @Bean
    public InitializingBean openfeignConfigGuard(FeignProps props) {
        return () -> {
            if (props.isHmacEnabled() && !StringUtils.hasText(props.getHmacSecret())) {
                throw new IllegalStateException(
                        "[CLOUD][FAIL-FAST] pivotos.cloud.openfeign.hmac.enabled=true 但未配置 hmac-secret，拒绝启动");
            }
            log.info("[CLOUD][openfeign] 通信抽象已装配：server-enabled={}, proxied={}, provider={}, hmac={}",
                    props.isServerEnabled(), props.getProxied(), props.getServiceInstanceProvider(),
                    props.isHmacEnabled());
        };
    }

    /** RPC 服务端点（仅 server-enabled=true 时注册；用 Servlet 而非 Controller，规避 MVC 扫描范围问题） */
    @Bean
    @ConditionalOnProperty(prefix = "pivotos.cloud.openfeign", name = "server-enabled", havingValue = "true")
    public ServletRegistrationBean<HttpServlet> facadeRpcServlet() {
        ServletRegistrationBean<HttpServlet> bean =
                new ServletRegistrationBean<>(new FacadeRpcServlet(), "/__rpc/*");
        bean.setName("pivotos-cloud-rpc-servlet");
        bean.setLoadOnStartup(1);
        return bean;
    }

    /** 把本地 Facade 实现 bean 换成远程代理（业务代码零改动的关键） */
    @Bean
    public FacadeProxyRegistrar facadeProxyRegistrar(FeignProps props) {
        return new FacadeProxyRegistrar(props);
    }

    static class FacadeProxyRegistrar implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware {
        private final FeignProps props;
        private ApplicationContext ctx;

        FacadeProxyRegistrar(FeignProps props) {
            this.props = props;
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            this.ctx = applicationContext;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            ClassLoader loader = ctx.getClassLoader();
            for (String ifaceName : props.proxiedInterfaces()) {
                try {
                    Class<?> iface = Class.forName(ifaceName, false, loader);
                    Set<String> candidates = new LinkedHashSet<>();
                    for (String beanName : registry.getBeanDefinitionNames()) {
                        BeanDefinition bd = registry.getBeanDefinition(beanName);
                        String className = bd.getBeanClassName();
                        if (className == null) {
                            continue;
                        }
                        Class<?> type;
                        try {
                            type = Class.forName(className, false, loader);
                        } catch (ClassNotFoundException ignored) {
                            continue;
                        }
                        if (iface.isAssignableFrom(type) && !type.isInterface()) {
                            candidates.add(beanName);
                        }
                    }
                    if (candidates.isEmpty()) {
                        log.warn("[CLOUD][openfeign] 未找到 {} 的本地实现，跳过代理", ifaceName);
                        continue;
                    }
                    for (String local : candidates) {
                        // 关键：本地实现副本必须退出「按类型自动装配候选」，否则 by-type 注入点会
                        // 同时命中代理与原实现 -> NoUniqueBeanDefinitionException（本次实测踩坑 K3）。
                        // 它只服务于 RPC 服务端点的「按名精确取 bean」，不参与任何注入解析。
                        String aliasBean = local + "$Local";
                        BeanDefinition copy = registry.getBeanDefinition(local);
                        copy.setAutowireCandidate(false);
                        registry.registerBeanDefinition(aliasBean, copy);
                        registry.removeBeanDefinition(local);
                        RootBeanDefinition proxyBd = new RootBeanDefinition(FacadeRemoteProxyFactoryBean.class);
                        proxyBd.getPropertyValues().add("facadeInterface", iface);
                        proxyBd.getPropertyValues().add("localBeanName", aliasBean);
                        registry.registerBeanDefinition(local, proxyBd);
                        RemoteFacadeRegistry.put(ifaceName, aliasBean);
                        log.info("[CLOUD][openfeign] Facade {} 已切换为远程代理：bean={}，本地实现别名={}",
                                iface.getSimpleName(), local, aliasBean);
                    }
                } catch (Exception e) {
                    log.warn("[CLOUD][openfeign] 处理 {} 失败：{}", ifaceName, e.getMessage());
                }
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // no-op
        }
    }

    /** 产出走 HTTP 的 Facade 代理对象 */
    public static class FacadeRemoteProxyFactoryBean implements FactoryBean<Object>, ApplicationContextAware {
        private Class<?> facadeInterface;
        private String localBeanName;
        private ApplicationContext ctx;

        public void setFacadeInterface(Class<?> facadeInterface) {
            this.facadeInterface = facadeInterface;
        }

        public void setLocalBeanName(String localBeanName) {
            this.localBeanName = localBeanName;
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) {
            this.ctx = applicationContext;
        }

        @Override
        public Class<?> getObjectType() {
            return facadeInterface;
        }

        @Override
        public Object getObject() {
            return Proxy.newProxyInstance(facadeInterface.getClassLoader(), new Class<?>[]{facadeInterface},
                    new FacadeRpcHandler(ctx, facadeInterface, localBeanName));
        }
    }

    /** 一次 Facade 调用 = 一次 HTTP JSON-RPC；失败时回落到本地实现并打 WARN（单体兜底，不掩盖问题） */
    static class FacadeRpcHandler implements InvocationHandler {
        private static final Logger handlerLog = LoggerFactory.getLogger(FacadeRpcHandler.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final ApplicationContext ctx;
        private final Class<?> iface;
        private final String localBeanName;

        FacadeRpcHandler(ApplicationContext ctx, Class<?> iface, String localBeanName) {
            this.ctx = ctx;
            this.iface = iface;
            this.localBeanName = localBeanName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            FeignProps props = ctx.getBean(FeignProps.class);
            long t0 = System.currentTimeMillis();
            try {
                Method target = resolveMethod(iface, method.getName(), args);
                Map<String, Object> bodyMap = new HashMap<>();
                bodyMap.put("interface", iface.getName());
                bodyMap.put("method", target.getName());
                List<String> paramTypes = new ArrayList<>();
                for (Class<?> pt : target.getParameterTypes()) {
                    paramTypes.add(pt.getName());
                }
                bodyMap.put("paramTypes", paramTypes);
                bodyMap.put("args", args == null ? List.of() : List.of(args));
                String body = MAPPER.writeValueAsString(bodyMap);

                String base = resolveTarget(props);
                String txId = TxContext.currentId();
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/__rpc/invoke"))
                        .header("Content-Type", "application/json")
                        .header("X-Rpc-Caller", "pivotos-admin-server")
                        .timeout(Duration.ofMillis(Math.max(1000, props.getTimeoutMs())))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (txId != null) {
                    req.header("X-Tx-Id", txId);
                }
                if (props.isHmacEnabled()) {
                    req.header("X-Rpc-Signature", Signer.sign(body, props.getHmacSecret()));
                }
                HttpResponse<String> resp = HttpClient.newHttpClient().send(req.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long cost = System.currentTimeMillis() - t0;
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + " body=" + resp.body());
                }
                Map<?, ?> result = MAPPER.readValue(resp.body(), Map.class);
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    throw new IllegalStateException(String.valueOf(result.get("error")));
                }
                JavaType returnType = MAPPER.getTypeFactory().constructType(method.getGenericReturnType());
                Object value = MAPPER.convertValue(result.get("data"), returnType);
                handlerLog.info("[CLOUD-RPC-CLIENT] {}#{} <- HTTP 200 ({}ms, base={})",
                        iface.getSimpleName(), method.getName(), cost, base);
                return value;
            } catch (Exception e) {
                handlerLog.warn("[CLOUD-RPC-CLIENT] {}#{} 远程调用失败，回落本地实现：{}",
                        iface.getSimpleName(), method.getName(), e.getMessage());
                Object local = ctx.getBean(localBeanName);
                return method.invoke(local, args);
            }
        }

        /**
         * 按「方法名 + 参数个数 + 形参可赋值」解析目标方法，而不是按实参类型的字面 class 去找。
         * 踩坑 K4：{@code iface.getMethod("chatStats", Integer.class)} 找不到形参为 {@code int} 的方法，
         * 表现为服务端 500 "IAiFacade.chatStats(java.lang.Integer)"（NoSuchMethodException）。
         */
        private Method resolveMethod(Class<?> iface, String name, Object[] args) throws NoSuchMethodException {
            Method fallback = null;
            for (Method m : iface.getMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != (args == null ? 0 : args.length)) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < pt.length; i++) {
                    Object a = args[i];
                    if (a == null) {
                        if (pt[i].isPrimitive()) {
                            match = false;
                        }
                        continue;
                    }
                    Class<?> boxed = pt[i];
                    if (boxed.isPrimitive()) {
                        boxed = box(boxed);
                    }
                    if (!boxed.isInstance(a)) {
                        match = false;
                    }
                }
                if (match) {
                    return m;
                }
                fallback = m;
            }
            if (fallback != null) {
                return fallback;
            }
            throw new NoSuchMethodException(iface.getName() + "." + name);
        }

        private static Class<?> box(Class<?> primitive) {
            if (primitive == int.class) {
                return Integer.class;
            }
            if (primitive == long.class) {
                return Long.class;
            }
            if (primitive == boolean.class) {
                return Boolean.class;
            }
            if (primitive == double.class) {
                return Double.class;
            }
            if (primitive == float.class) {
                return Float.class;
            }
            if (primitive == short.class) {
                return Short.class;
            }
            if (primitive == byte.class) {
                return Byte.class;
            }
            if (primitive == char.class) {
                return Character.class;
            }
            return primitive;
        }

        private String resolveTarget(FeignProps props) {
            for (ServiceInstanceProvider provider : ctx.getBeansOfType(ServiceInstanceProvider.class).values()) {
                if (props.getServiceInstanceProvider().equals(provider.id())) {
                    return provider.resolve("pivotos-admin-server");
                }
            }
            return props.getBaseUrl();
        }
    }

    /** RPC 服务端点：反射调用「本地实现 bean」并 JSON 返回 —— 等价于远端实例里的那个 bean */
    static class FacadeRpcServlet extends HttpServlet {
        private static final Logger servletLog = LoggerFactory.getLogger(FacadeRpcServlet.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setCharacterEncoding("UTF-8");
            resp.setContentType("application/json;charset=UTF-8");
            try {
                String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                ApplicationContext ctx =
                        WebApplicationContextUtils.getRequiredWebApplicationContext(getServletContext());
                var props = ctx.getBean(FeignProps.class);
                if (props.isHmacEnabled()
                        && !Signer.valid(body, req.getHeader("X-Rpc-Signature"), props.getHmacSecret())) {
                    resp.setStatus(401);
                    resp.getWriter().write("{\"ok\":false,\"error\":\"signature invalid\"}");
                    return;
                }
                Map<?, ?> payload = MAPPER.readValue(body, Map.class);
                String ifaceName = String.valueOf(payload.get("interface"));
                String methodName = String.valueOf(payload.get("method"));
                @SuppressWarnings("unchecked")
                List<String> paramTypes = (List<String>) payload.get("paramTypes");
                @SuppressWarnings("unchecked")
                List<Object> args = (List<Object>) payload.get("args");

                Class<?> iface = Class.forName(ifaceName);
                String alias = RemoteFacadeRegistry.localAlias(ifaceName);
                Object target = alias != null ? ctx.getBean(alias) : ctx.getBean(iface);

                Class<?>[] types = new Class<?>[paramTypes.size()];
                for (int i = 0; i < types.length; i++) {
                    types[i] = paramTypes.get(i) == null ? Object.class : toClass(paramTypes.get(i));
                }
                Method method = iface.getMethod(methodName, types);
                Object data = method.invoke(target, args == null ? new Object[0] : args.toArray());
                String json = MAPPER.writeValueAsString(Map.of("ok", true, "data", data));
                resp.getWriter().write(json);
                servletLog.info("[CLOUD-RPC-SERVER] {}#{} -> 200", iface.getSimpleName(), methodName);
            } catch (Exception e) {
                servletLog.warn("[CLOUD-RPC-SERVER] 调用失败：{}", e.getMessage());
                resp.setStatus(500);
                resp.getWriter().write("{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        /** Class.forName 不认识 "int"/"long" 这类基本类型名，需要显式映射（配合客户端 POJO 解析） */
        private static Class<?> toClass(String name) throws ClassNotFoundException {
            switch (name) {
                case "int":
                    return int.class;
                case "long":
                    return long.class;
                case "boolean":
                    return boolean.class;
                case "double":
                    return double.class;
                case "float":
                    return float.class;
                case "short":
                    return short.class;
                case "byte":
                    return byte.class;
                case "char":
                    return char.class;
                default:
                    return Class.forName(name);
            }
        }
    }
}

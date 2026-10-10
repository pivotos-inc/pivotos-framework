package com.pivotos.starter.cloud.api.facade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pivotos.starter.cloud.api.config.CloudProperties;
import com.pivotos.starter.cloud.api.context.CloudContextCodec;
import com.pivotos.starter.cloud.api.support.FacadeInvocationSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;

/**
 * Facade 契约的 HTTP 适配端点（方案 C：契约层零改动，运行时反射注册）。
 *
 * <p><b>路由形态</b>：{@code POST <path>/{接口全限定名}/{方法名}}，body 为 JSON 参数数组
 * （{@code []} 表示无参），响应 {@code {"ok":true,"data":...}} / {@code {@code "ok":false,"error":...}}。
 *
 * <p><b>安全口径（与 local 通道的 {@code /__rpc/invoke} 完全一致，三条一条都不能让）</b>：
 * <ol>
 *   <li>默认不注册（{@code pivotos.cloud.facade-http.enabled=false}）；</li>
 *   <li>注册时启动期 FAIL-FAST 校验已配 {@code pivotos.cloud.context.internal-token}（见 AutoConfiguration）；</li>
 *   <li>每次请求必须携带与之一致的 {@code X-PivotOS-Internal} 头，否则 403 且不执行任何反射。</li>
 * </ol>
 * 否则这个端点等于「按 URL 反射调用任意 Bean 方法」的公开后门——比不做 HTTP 化危险得多。
 *
 * <p><b>为什么刻意<b>不加</b> {@code @RestController}</b>：加了它就成了组件扫描的直接命中目标——
 * 只要宿主应用扫到 {@code com.pivotos..}（admin-server 就是这么扫的），
 * 哪怕自动配置的条件不成立，这个端点也会被注册上去，「默认形态零行为」当场失效
 * （本轮实测：同包 test app 直接把关掉的端点又扫起来了，返回 200）。
 * 因此改用 {@link FacadeHttpMappingRegistrar} 在自动配置里<b>以编程方式注册映射</b>，
 * 「是否出现在端口上」完全由 {@code FacadeHttpAutoConfiguration} 的条件说了算。
 *
 * <p><b>为什么不直接让实现类实现 Facade 接口、由 Spring 绑定参数</b>：那等于给契约接口加
 * {@code @PostMapping}（方案 A），需要 8 个 {@code *-api} 模块引入 {@code spring-web}，
 * 且 Spring MVC 对接口上的<b>参数注解</b>取的是实现类方法，复杂入参会静默退化成表单绑定。
 * 反射适配虽牺牲了标准 REST 观感，换来的是契约层纯粹与统一的参数 marshalling 口径。
 *
 * <p><b>ScopedValue 上下文在此已就位</b>：本端点始终处在 {@code CloudContextFilter} 之后，
 * 因此方法体里读到的 {@code TenantContext}/{@code LoginContext} 就是「入站恢复后的终态」，
 * 这正是它能充当跨进程传播取证探针的原因。
 */
public class FacadeHttpHandler {

    private static final Logger log = LoggerFactory.getLogger(FacadeHttpHandler.class);

    private final ApplicationContext context;
    private final String expectedToken;
    private final FacadeHttpProperties properties;
    private final ObjectMapper mapper = createMapper();

    /**
     * 只接受 {@link CloudProperties} 而<b>不另开 {@code FacadeHttpProperties} 注入点</b>：
     * 后者是 {@code CloudProperties} 的嵌套配置对象，并不会被注册成独立 Bean
     * （实测直接按类型注入会 NoSuchBeanDefinitionException）。
     */
    public FacadeHttpHandler(ApplicationContext context, CloudProperties cloudProps) {
        this.context = context;
        this.expectedToken = cloudProps.getContext().getInternalToken();
        this.properties = cloudProps.getFacadeHttp();
    }

    /**
     * 统一入口。方法/参数注解保留在此是为了让编程式注册的 {@code HandlerMethod}
     * 拿得到绑定元数据；路径本身由 {@link FacadeHttpMappingRegistrar} 决定。
     */
    public ResponseEntity<String> invoke(@PathVariable("iface") String iface,
                                         @PathVariable("method") String method,
                                         @RequestBody(required = false) String rawArgs,
                                         HttpServletRequest request) {
        if (!CloudContextCodec.isInternalCall(request, expectedToken)) {
            log.warn("[CLOUD][facade-http] 拒绝未通过内部凭证校验的调用：iface={}, method={}", iface, method);
            return status(HttpStatus.FORBIDDEN, FacadeHttpConstants.ERROR_FORBIDDEN);
        }
        if (!properties.getInterfaces().contains(iface)) {
            // 不在白名单与「本进程没这个类」返回同一个码：不让调用方探测 classpath
            log.warn("[CLOUD][facade-http] 接口不在白名单：{}", iface);
            return status(HttpStatus.NOT_FOUND, FacadeHttpConstants.ERROR_NOT_FOUND);
        }

        JsonNode argsNode = parseArgs(rawArgs);
        if (argsNode == null) {
            argsNode = mapper.createArrayNode();
        }
        if (argsNode.size() > properties.getMaxArgs()) {
            return status(HttpStatus.BAD_REQUEST, FacadeHttpConstants.ERROR_TOO_MANY_ARGS);
        }

        Class<?> ifaceType;
        try {
            ifaceType = ClassUtils.forName(iface, context.getClassLoader());
        } catch (Exception e) {
            return status(HttpStatus.NOT_FOUND, FacadeHttpConstants.ERROR_NOT_FOUND);
        }

        Object target = FacadeInvocationSupport.resolveTarget(context, ifaceType);
        if (target == null) {
            return status(HttpStatus.NOT_FOUND, FacadeHttpConstants.ERROR_NOT_FOUND);
        }

        Method targetMethod = FacadeInvocationSupport.resolveMethod(ifaceType, method, argsNode.size());
        if (targetMethod == null) {
            return status(HttpStatus.NOT_FOUND, FacadeHttpConstants.ERROR_NO_METHOD);
        }

        try {
            Object[] args = convertArgs(targetMethod, argsNode);
            Object result = targetMethod.invoke(target, args);
            return ResponseEntity.ok(FacadeHttpConstants.ok(mapper, result));
        } catch (Exception e) {
            log.warn("[CLOUD][facade-http] 调用失败：{}#{}", iface, method, e);
            return status(HttpStatus.INTERNAL_SERVER_ERROR, FacadeHttpConstants.ERROR_INVOCATION);
        }
    }

    private JsonNode parseArgs(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(rawArgs);
            return node != null && node.isArray() ? node : null;
        } catch (Exception e) {
            // body 不是 JSON 数组一律按「无参」处理：元数不匹配会走 NO_METHOD，不会误调
            return null;
        }
    }

    private Object[] convertArgs(Method method, JsonNode argsNode) {
        int count = method.getParameterCount();
        if (count == 0) {
            return new Object[0];
        }
        if (argsNode == null) {
            return new Object[count];
        }
        Object[] args = new Object[count];
        for (int i = 0; i < count; i++) {
            JsonNode node = argsNode.get(i);
            if (node == null || node.isNull()) {
                args[i] = null;
                continue;
            }
            args[i] = mapper.convertValue(node, wrapperOf(method.getParameterTypes()[i]));
        }
        return args;
    }

    private Class<?> wrapperOf(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        return type == char.class ? Character.class : type;
    }

    private ResponseEntity<String> status(HttpStatus httpStatus, String error) {
        return ResponseEntity.status(httpStatus)
            .contentType(MediaType.APPLICATION_JSON)
            .body(FacadeHttpConstants.fail(mapper, error));
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}

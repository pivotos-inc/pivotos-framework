package com.pivotos.starter.cloud.api.facade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pivotos.common.core.exception.ServiceException;
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

import java.lang.reflect.Method;
import java.lang.reflect.Type;

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
    private final FacadeHttpWhitelist whitelist;
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
        this.whitelist = FacadeHttpWhitelist.parse(properties.getInterfaces());
    }

    /** 解析后的白名单（供 {@link FacadeHttpMappingRegistrar} 做启动期自检） */
    public FacadeHttpWhitelist whitelist() {
        return whitelist;
    }

    /**
     * 统一入口。方法/参数注解保留在此是为了让编程式注册的 {@code HandlerMethod}
     * 拿得到绑定元数据；路径本身由 {@link FacadeHttpMappingRegistrar} 决定。
     *
     * <p><b>为什么参数里没有 {@code @RequestBody String}</b>（V3-S4 实测踩坑）：
     * 让容器把 body 绑成 {@code String} 时，容器的 JSON 转换器会按「目标类型是 String」
     * 去解析，于是 {@code ["非本人待办"]} 被<b>静默摊平</b>成 {@code 非本人待办}
     * （单元素数组 → 标量），参数个数从 1 变成 0，方法定位直接 NO_METHOD。
     * 更阴的是：2 个及以上元素的数组走另一条转换器分支、行为正常 ——
     * 所以「多参数方法能调通、单参数方法全废」这个形态极难归因。
     * V3-S3 试点期用的是 {@code ContextFacade}，四个方法全是 0 参（body 恒为 {@code []}），
     * <b>这个缺陷永远暴露不出来</b> —— 这也是「业务 Facade 必须先验证再铺开」的理由。
     * 因此这里直接读 {@code HttpServletRequest} 的原始字节，彻底不受容器转换器口味影响。
     */
    public ResponseEntity<String> invoke(@PathVariable("iface") String iface,
                                         @PathVariable("method") String method,
                                         HttpServletRequest request) {
        if (!CloudContextCodec.isInternalCall(request, expectedToken)) {
            log.warn("[CLOUD][facade-http] 拒绝未通过内部凭证校验的调用：iface={}, method={}", iface, method);
            return status(HttpStatus.FORBIDDEN, FacadeHttpConstants.ERROR_FORBIDDEN);
        }
        if (!whitelist.allows(iface, method)) {
            // 不在白名单 / 方法不在白名单 / 本进程没这个类，三者返回同一个码：
            // 不让调用方通过响应差异探测「某个接口到底在不在白名单里」
            log.warn("[CLOUD][facade-http] 接口或方法不在白名单：{}#{}", iface, method);
            return status(HttpStatus.NOT_FOUND, FacadeHttpConstants.ERROR_NOT_FOUND);
        }

        JsonNode argsNode = parseArgs(readBody(request));
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

        Object[] args;
        try {
            args = convertArgs(targetMethod, argsNode);
        } catch (Exception e) {
            // 形参反序列化失败是「调用方把参数写错了」，不是业务失败：给 400 让它去改 body，
            // 别打成 500 —— 500 会诱导调用方重试，而重试永远也只是换个姿势失败。
            log.warn("[CLOUD][facade-http] 参数反序列化失败：{}#{}", iface, method, e);
            return status(HttpStatus.BAD_REQUEST, FacadeHttpConstants.ERROR_BAD_ARGS);
        }
        try {
            Object result = targetMethod.invoke(target, args);
            return ResponseEntity.ok(FacadeHttpConstants.ok(mapper, result));
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            if (cause instanceof ServiceException se) {
                // 业务异常：HTTP 200 + ok=false + 业务码与文案（与 local 通道同口径）
                log.warn("[CLOUD][facade-http] 业务异常：{}#{} code={} msg={}",
                    iface, method, se.getCode(), se.getMessage());
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(FacadeHttpConstants.serviceFail(mapper, se.getCode(), se.getMessage()));
            }
            log.warn("[CLOUD][facade-http] 调用失败：{}#{}", iface, method, e);
            return status(HttpStatus.INTERNAL_SERVER_ERROR, FacadeHttpConstants.ERROR_INVOCATION);
        }
    }

    /**
     * 反射调用抛的是 {@code InvocationTargetException}，真正的异常在它的 cause 里。
     * 不解包的话 {@code instanceof ServiceException} 永远为 false，
     * 业务异常会全部退化成 500 + INVOCATION_FAILED（V3-S4 D3）。
     */
    private static Throwable unwrap(Throwable e) {
        Throwable current = e;
        while (current instanceof java.lang.reflect.InvocationTargetException
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 直读请求体原始字节。
     *
     * <p>JSON 的字符集定义就是 UTF-8，容器没给编码时按 UTF-8 解（而不是 servlet 规范默认的
     * ISO-8859-1）—— 否则中文参数会被解成一串乱码字符，且仍能通过 JSON 解析，
     * 故障形态会伪装成「业务查不到这条数据」。
     */
    private String readBody(HttpServletRequest request) {
        try {
            String encoding = request.getCharacterEncoding();
            java.nio.charset.Charset charset = (encoding == null || encoding.isBlank())
                ? java.nio.charset.StandardCharsets.UTF_8
                : java.nio.charset.Charset.forName(encoding);
            return new String(request.getInputStream().readAllBytes(), charset);
        } catch (Exception e) {
            log.warn("[CLOUD][facade-http] 读取请求体失败，按无参处理", e);
            return null;
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
        Type[] genericTypes = method.getGenericParameterTypes();
        for (int i = 0; i < count; i++) {
            JsonNode node = argsNode.get(i);
            if (node == null || node.isNull()) {
                args[i] = null;
                continue;
            }
            // ⚠️ 必须用 getGenericParameterTypes() 而不是 getParameterTypes()：
            // 后者丢掉泛型实参，Collection<Long> 会被反序列化成 ArrayList<Object>，
            // 元素实际是 Integer —— 反射调用时泛型擦除不报错，
            // 但实现里 `for (Long id : ids)` 立刻 ClassCastException（V3-S4 D1）。
            // local 通道的 FacadeRpcServlet 早就走的是泛型版本，HTTP 端点在这里没跟上，
            // 两条通道的 marshalling 口径必须一致。
            args[i] = mapper.convertValue(node, mapper.getTypeFactory().constructType(genericTypes[i]));
        }
        return args;
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

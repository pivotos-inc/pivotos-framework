package com.pivotos.monitor.controller;

import com.pivotos.monitor.config.OpsHealthConfig;
import com.pivotos.monitor.service.OpsHealthService;
import com.pivotos.monitor.support.HealthPayload;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探活端点的「形态」守卫（v3.1.0 生产化 · A2）。
 *
 * <p>这里守的是两个容易被顺手改坏、且改坏了<b>编译期零信号</b>的点：
 * <ol>
 *   <li>路径必须是 {@code /health/live} 与 {@code /health/ready}——Nginx / k8s 的 probe 配置、
 *       {@code scripts/ops/start-prod.sh} 的就绪轮询、本轮新增的 E2E 全都写死这两条，
 *       改路径等于让线上探活静默失效。</li>
 *   <li>两个方法都不得挂 Sa-Token 鉴权注解——探活要能被 Nginx 匿名打，
 *       若有人「顺手加个 @SaCheckLogin」，本地开发用 curl 从来试不出来
 *       （curl 不带 token 时端点会返回非 0 业务码，看起来就像「服务没起来」）。</li>
 * </ol>
 * 匿名可见面不是靠鉴权注解守，而是靠 {@link HealthPayload} 的字段裁剪（彼处有单测钉死）。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A2）
 */
class OpsHealthControllerMappingTest {

    @Test
    void basePathIsHealthAndTwoReadEndpointsExist() {
        RequestMapping mapping = OpsHealthController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "控制器必须声明 @RequestMapping(\"/health\")");
        assertEquals("/health", Arrays.toString(mapping.value()).replace("[", "").replace("]", ""));

        Method live = method("live");
        Method ready = method("ready");
        assertEquals("/live", live.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/ready", ready.getAnnotation(GetMapping.class).value()[0]);
        assertNotNull(OpsHealthController.class.getAnnotation(RestController.class));
    }

    @Test
    void probeEndpointsMustStayAnonymous() {
        for (Method m : List.of(method("live"), method("ready"))) {
            boolean saTokenAnnotated = Arrays.stream(m.getAnnotations())
                    .anyMatch(a -> a.annotationType().getName().startsWith("cn.dev33.satoken.annotation"));
            assertFalse(saTokenAnnotated,
                    m.getName() + " 不得挂 Sa-Token 鉴权注解：Nginx/k8s 探活是匿名请求，"
                            + "加了鉴权本地 curl 试不出来，线上表现为「轮询一直不就绪」");
        }
    }

    @Test
    void probeCanBeSwitchedOff() {
        ConditionalOnProperty controller = OpsHealthController.class.getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty config = OpsHealthConfig.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(controller, "端点要能整体关掉（关掉 = 不注册 Bean = 走既有 200 + code=1004）");
        assertNotNull(config, "装配也要挂同一个开关，否则「关了端点但 Bean 还在」");
        assertEquals("pivotos.ops.health", controller.prefix());
        assertEquals("enabled", controller.name()[0]);
        assertTrue(controller.matchIfMissing());
        assertEquals(controller.matchIfMissing(), config.matchIfMissing());
    }

    @Test
    void serviceDependencyStaysInMonitorOnly() {
        // 探活只落在 monitor 插件：这里是唯一同时依赖 web/mybatis/redisson/search 契约的插件，
        // 放到 starter 会引入 starter→plugin 的反向依赖（ArchUnit A3 红线）
        assertEquals("com.pivotos.monitor.service", OpsHealthService.class.getPackageName());
    }

    private static Method method(String name) {
        try {
            return OpsHealthController.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("/health/" + name + " 端点不见了（改路径会让线上探活静默失效）", e);
        }
    }
}

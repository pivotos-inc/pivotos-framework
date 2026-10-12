package com.pivotos.migration.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.starter.auth.account.StpSysUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移域三个控制器的鉴权守卫（v3.1.0 生产化 · A4）。
 *
 * <p>实证（本轮全量匿名扫描，140 个 GET 实打）：{@code /migration/task/page}、
 * {@code /migration/step/list?taskId=1}、{@code /migration/artifact/list?stepId=1}
 * 三个端点匿名即 {@code code=0}——其中后两个是原 §10-K 扫描漏掉的（当时只打无参 GET）。
 *
 * <p>为什么是<b>静态反射</b>而不是起容器打接口：这里要钉的是<b>「类级」</b>注解——
 * 只有类级注解才能让今后新增的 handler 方法自动继承鉴权；方法级注解每加一个方法
 * 就可能漏一次，正是 S5「漏鉴权零信号」的成因。反射断言对此零成本且不会因环境抖动假红。
 *
 * <p>运行时行为（匿名真的拿不到数据）由 {@code scripts/e2e/anonymous_endpoint_audit.py}
 * 全量实打覆盖，两者分工：这里防回退，那里证实效。
 */
class MigrationControllerAuthTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            MigrationTaskController.class, MigrationStepController.class, MigrationArtifactController.class);

    @Test
    void 迁移域三个控制器必须有类级登录校验() {
        for (Class<?> controller : CONTROLLERS) {
            SaCheckLogin login = controller.getAnnotation(SaCheckLogin.class);
            assertNotNull(login, controller.getSimpleName() + " 必须有类级 @SaCheckLogin"
                    + "（方法级注解会让新增方法再次漏鉴权）");
            assertEquals(StpSysUtil.TYPE, login.type(),
                    controller.getSimpleName() + " 的 type 必须显式为 StpSysUtil.TYPE："
                            + "默认 realm 是 login，管端 token 会被判未登录（1002）");
        }
    }

    @Test
    void 不得使用权限码注解_否则超管也会被拒() {
        for (Class<?> controller : CONTROLLERS) {
            assertNull(controller.getAnnotation(SaCheckPermission.class),
                    controller.getSimpleName() + " 不得用 @SaCheckPermission："
                            + "migration 权限码尚未进 sys_menu，用了会让 super_admin 也 403（S124 同款口径）");
        }
    }

    @Test
    void 路由前缀不得漂移() {
        assertEquals("/migration/task", prefixOf(MigrationTaskController.class));
        assertEquals("/migration/step", prefixOf(MigrationStepController.class));
        assertEquals("/migration/artifact", prefixOf(MigrationArtifactController.class));
    }

    @Test
    void 进度流仍是POST_且类级鉴权对它同样成立() {
        Method progress = Arrays.stream(MigrationTaskController.class.getDeclaredMethods())
                .filter(m -> "progress".equals(m.getName()))
                .findFirst().orElseThrow();
        PostMapping mapping = progress.getAnnotation(PostMapping.class);
        assertNotNull(mapping, "进度流必须是 POST（EventSource 带不了 Authorization，前端用 fetch + ReadableStream）");
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, mapping.produces()[0]);
        // 类级 @SaCheckLogin 对 SSE 一样生效：前端 subscribeMigrationProgress 显式带 Authorization，
        // 本测试不重复断言运行时行为（由 E2E 覆盖），只保证签名没被改回 GET。
    }

    @Test
    void 每个控制器都确实有handler方法_防空壳误绿() {
        for (Class<?> controller : CONTROLLERS) {
            long handlers = Arrays.stream(controller.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                            || m.isAnnotationPresent(PostMapping.class))
                    .count();
            assertTrue(handlers > 0, controller.getSimpleName() + " 未找到任何 handler 方法，断言可能失效");
        }
    }

    private String prefixOf(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, controller.getSimpleName() + " 缺少 @RequestMapping");
        return mapping.value()[0];
    }
}

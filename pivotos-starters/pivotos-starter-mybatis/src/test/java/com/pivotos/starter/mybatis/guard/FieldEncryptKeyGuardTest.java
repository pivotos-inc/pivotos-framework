package com.pivotos.starter.mybatis.guard;

import com.pivotos.starter.mybatis.config.MybatisPlusAutoConfiguration;
import com.pivotos.starter.mybatis.config.properties.MybatisProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段加密密钥守卫（V3.1-D 遗留③ / C3-1）。
 *
 * <p>守的是一条「改了能起服、也能跑到生产上，但永远没人发现」的线：
 * prod 忘了注入 PIVOTOS_FIELD_ENCRYPT_KEY 时，应用会用 git 里明文的开发默认密钥照常加解密，
 * 字段加密形同虚设且零信号。故 prod 形态必须拒绝启动，dev/test 必须行为零变化。
 */
class FieldEncryptKeyGuardTest {

    private static final String CUSTOM_32 = "abcdefghijklmnopqrstuvwxyz012345";

    /**
     * 构造测试用环境。
     *
     * <p>坑（本轮实踩）：<b>不要用 {@code System.setProperty} 写守卫开关</b>——surefire 同一 JVM 里
     * 测试方法顺序不保证，「关掉守卫」那条若先跑，会把全局系统属性留在 JVM 里，
     * 让「prod 必须拒绝启动」那条变成假绿/假红。这里一律用 {@code MapPropertySource} 注入，
     * 每个 Environment 自带一份，互不污染。
     */
    private static StandardEnvironment envOf(String profile, Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        if (!props.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("test-props", props));
        }
        return env;
    }

    private static StandardEnvironment envOf(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        return env;
    }

    private static MybatisProperties props(String key) {
        MybatisProperties props = new MybatisProperties();
        props.setFieldEncryptKey(key);
        return props;
    }

    // ────────── 纯函数口径 ──────────

    @Test
    @DisplayName("C3-1-1：prod + 开发默认密钥 → 判定为拒绝启动")
    void prodWithDevDefaultIsRejected() {
        assertEquals(FieldEncryptKeyGuard.Verdict.PROD_DEFAULT_REJECTED,
                FieldEncryptKeyGuard.evaluate(true, FieldEncryptKeyGuard.DEV_DEFAULT_KEY, true));
        String rejection = FieldEncryptKeyGuard.rejection(envOf("prod"), FieldEncryptKeyGuard.DEV_DEFAULT_KEY);
        assertNotNull(rejection, "prod 形态用开发默认密钥必须拒绝启动");
        assertTrue(rejection.contains("PIVOTOS_FIELD_ENCRYPT_KEY"), "拒绝信息必须告诉运维该注入哪个变量");
    }

    @Test
    @DisplayName("C3-1-2：prod + 自定义密钥 → 放行")
    void prodWithCustomKeyIsAllowed() {
        assertEquals(FieldEncryptKeyGuard.Verdict.OK,
                FieldEncryptKeyGuard.evaluate(true, CUSTOM_32, true));
        assertNull(FieldEncryptKeyGuard.rejection(envOf("prod"), CUSTOM_32));
    }

    @Test
    @DisplayName("C3-1-3：dev/test + 开发默认密钥 → 只告警，不阻断（零行为变化是硬要求）")
    void nonProdWithDevDefaultIsOnlyWarn() {
        assertEquals(FieldEncryptKeyGuard.Verdict.DEV_DEFAULT_WARN,
                FieldEncryptKeyGuard.evaluate(false, FieldEncryptKeyGuard.DEV_DEFAULT_KEY, true));
        assertNull(FieldEncryptKeyGuard.rejection(envOf("dev"), FieldEncryptKeyGuard.DEV_DEFAULT_KEY));
        assertNull(FieldEncryptKeyGuard.rejection(envOf("test"), FieldEncryptKeyGuard.DEV_DEFAULT_KEY));
        assertTrue(FieldEncryptKeyGuard.usesDevDefault(FieldEncryptKeyGuard.DEV_DEFAULT_KEY));
    }

    @Test
    @DisplayName("C3-1-4：未配置密钥（字段加密不启用）一律不管")
    void missingKeyIsIgnored() {
        assertEquals(FieldEncryptKeyGuard.Verdict.OK, FieldEncryptKeyGuard.evaluate(true, null, true));
        assertEquals(FieldEncryptKeyGuard.Verdict.OK, FieldEncryptKeyGuard.evaluate(true, "", true));
        assertNull(FieldEncryptKeyGuard.rejection(envOf("prod"), null));
    }

    @Test
    @DisplayName("C3-1-5：紧急绕过开关生效（事故窗口可显式摘除）")
    void guardCanBeDisabled() {
        assertEquals(FieldEncryptKeyGuard.Verdict.OK,
                FieldEncryptKeyGuard.evaluate(true, FieldEncryptKeyGuard.DEV_DEFAULT_KEY, false));
        StandardEnvironment env =
                envOf("prod", Map.of(FieldEncryptKeyGuard.GUARD_ENABLED_KEY, "false"));
        assertNull(FieldEncryptKeyGuard.rejection(env, FieldEncryptKeyGuard.DEV_DEFAULT_KEY));
    }

    // ────────── 与自动配置的接线（证明不只是写了个工具类） ──────────

    @Test
    @DisplayName("C3-1-6：prod + 默认密钥 → 自动配置构造即抛异常（起服失败）")
    void prodWithDefaultKeyBreaksContext() {
        assertThrows(IllegalStateException.class,
                () -> new MybatisPlusAutoConfiguration(props(FieldEncryptKeyGuard.DEV_DEFAULT_KEY), envOf("prod")));
    }

    @Test
    @DisplayName("C3-1-7：prod + 自定义密钥 → 正常构造")
    void prodWithCustomKeyStarts() {
        assertDoesNotThrow(() -> new MybatisPlusAutoConfiguration(props(CUSTOM_32), envOf("prod")));
    }

    @Test
    @DisplayName("C3-1-8：dev + 默认密钥 → 正常构造（IT 与本地开发零影响）")
    void devWithDefaultKeyStarts() {
        assertDoesNotThrow(() -> new MybatisPlusAutoConfiguration(
                props(FieldEncryptKeyGuard.DEV_DEFAULT_KEY), envOf("dev")));
    }
}

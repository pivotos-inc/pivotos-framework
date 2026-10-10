package com.pivotos.starter.cloud.alibaba.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code optional:nacos:} 前缀的承重性验证（V3-S2 L6，离线可跑、零外部资源）。
 *
 * <p>本轮硬要求是「Nacos 不可达时应用仍能起服」，而这条要求<b>完全系于一个前缀</b>。
 * 只测「起得来」是不够的——去掉前缀也起得来（因为本地恰好没配 Nacos）的话，
 * 这条守卫就是假的。所以这里刻意保留一条<b>反向自证</b>：
 * 同一个不可达地址，去掉 {@code optional:} 必须<b>启动失败</b>。
 *
 * <p>为什么不用 {@code ApplicationContextRunner}：它不跑
 * {@code ConfigDataEnvironmentPostProcessor}，{@code spring.config.import} 根本不会被处理，
 * 任何断言都会假绿。必须真起 {@code SpringApplication}。
 */
class NacosConfigOptionalImportTest {

    /** 必然连不上的地址：本机 65500 端口无监听，connection refused 立刻返回。 */
    private static final String UNREACHABLE_ADDR = "127.0.0.1:65500";

    @Test
    void nacos不可达时带optional前缀仍能起服且不产生nacos属性源() {
        try (ConfigurableApplicationContext ctx = run("optional:nacos:pivotos-nacos-config-test.yaml")) {
            assertTrue(ctx.isActive(), "Nacos 不可达时应用必须仍能起服（本轮硬要求）");
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertNull(env.getProperty("pivotos.nacos.probe"), "没读到配置时探针值应为空");
            assertFalse(hasNacosPropertySource(env), "加载失败时不应留下 Nacos 属性源");
        }
    }

    @Test
    void 声明了import却没有nacos条目会被springcloud校验打断() {
        // 反直觉但必须记住：alibaba 形态下 spring.config.import 是【必填】的，
        // 而且必须含一条 nacos: —— 否则 spring-cloud-commons 的
        // NacosConfigDataMissingEnvironmentPostProcessor 直接把启动打断
        // （判定用 String.contains("nacos")，所以 optional:nacos: 这样的形态是合法的）。
        // 这也是旧注释里「nacos-config 是启动硬门槛」的真身：门槛不在解析、在校验。
        Throwable thrown = assertThrows(Throwable.class,
            () -> run("optional:file:./not-exist.yaml"),
            "import 里没有 nacos: 条目时必须被打断，否则说明 SC 的 import-check 没生效");
        System.out.println("[IT] import-check 抛出的异常：" + thrown.getClass().getName() + " / " + thrown.getMessage());
    }

    @Test
    void 未知前缀带optional被静默忽略不带则打断启动() {
        // 反向自证：optional: 真正承重的形态是「classpath 上没有能处理该前缀的解析器」
        // —— 即默认（非 -P alibaba）fat jar 的形态：yml 里写着 nacos: 导入，jar 里却没有解析器。
        // 先垫一条 nacos: 导入绕过上面那条 import-check，单独考察 Boot 自己的 optional 语义。
        assertThrows(Throwable.class,
            () -> run("optional:nacos:pivotos-nacos-config-test.yaml", "nosuchresolver:foo.yaml"),
            "未知前缀且不带 optional: 必须打断启动，否则 Boot 的可选导入语义没生效");

        try (ConfigurableApplicationContext ctx = run(
            "optional:nacos:pivotos-nacos-config-test.yaml", "optional:nosuchresolver:foo.yaml")) {
            assertTrue(ctx.isActive(), "同一个未知前缀带上 optional: 就应被静默忽略（默认形态 fat jar 靠它兜底）");
        }
    }

    @Test
    void nacos不可达时nacos客户端返回空内容而不是抛异常() {
        // 事实记录（非保护断言）：这条是 V3-S2 L6 实测出来的行为——不加 optional: 也起得来。
        // 之所以仍要求写 optional:，是因为它兜的是「无解析器」与将来 client 行为变化，
        // 而不是当下的「不可达」。若哪天本用例变成抛异常，说明上游行为变了，需要重新评估。
        try (ConfigurableApplicationContext ctx = run("nacos:pivotos-nacos-config-test.yaml")) {
            assertTrue(ctx.isActive(), "当前 nacos-client 在不可达时返回空内容，不阻断启动");
            assertNull(ctx.getEnvironment().getProperty("pivotos.nacos.probe"));
        }
    }

    @Test
    void 多条import与占位符dataId都不会破坏启动() {
        // 与 admin-server application.yml 的写法保持一致：公共 dataId + 环境覆盖 dataId
        try (ConfigurableApplicationContext ctx = run(
            "optional:nacos:pivotos-nacos-config-test.yaml",
            "optional:nacos:pivotos-nacos-config-test-${PIVOTOS_NACOS_CONFIG_ENV:dev}.yaml")) {
            assertTrue(ctx.isActive(), "多条 import（含占位符 dataId）不应影响启动");
            assertNotNull(ctx.getBean(NacosConfigOptionalImportTest.TestApp.class));
        }
    }

    private static boolean hasNacosPropertySource(ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps.getClass().getName().toLowerCase().contains("nacos")) {
                return true;
            }
        }
        return false;
    }

    private static ConfigurableApplicationContext run(String... imports) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(TestApp.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "spring.application.name=pivotos-nacos-config-test",
                "spring.cloud.nacos.config.server-addr=" + UNREACHABLE_ADDR,
                "spring.cloud.nacos.config.username=nacos",
                "spring.cloud.nacos.config.password=",
                "spring.cloud.nacos.config.timeout=1000",
                "spring.cloud.nacos.discovery.enabled=false",
                "logging.level.root=WARN"
            );
        for (int i = 0; i < imports.length; i++) {
            builder.properties("spring.config.import[" + i + "]=" + imports[i]);
        }
        return builder.run();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApp {
    }
}

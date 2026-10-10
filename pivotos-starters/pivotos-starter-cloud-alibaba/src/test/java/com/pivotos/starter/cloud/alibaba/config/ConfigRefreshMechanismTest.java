package com.pivotos.starter.cloud.alibaba.config;

import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态刷新机制的离线取证（V3-S3 第 ⑥ 项的<b>默认回归集部分</b>）。
 *
 * <p><b>为什么这一段可以离线测</b>：Nacos 侧只负责「发现远端内容变了然后发布
 * {@link RefreshEvent}」，真正的重绑定由 spring-cloud-context 的刷新链路完成。
 * 把这一步钉死后，「改配置不生效」的排查范围能立刻收敛到「Nacos 有没有发布事件」，
 * 而不必每次连 dev Nacos 试上半小时。
 *
 * <p><b>结论先行</b>：本机依赖组合（SCA 2025.1.0.0 → spring-cloud-context 5.0.3）
 * 具备完整刷新能力——{@code @ConfigurationProperties} 会重新绑定、
 * {@code @RefreshScope} Bean 会重建。所以 ⑥ 的答案不是「不支持」，
 * 而是「支持，但只有被 {@code @ConfigurationProperties} / {@code @RefreshScope} 标注的 Bean 生效」：
 * <b>把远端值注入普通单例 Bean 的字段上并不会变</b>——这一条必须写进产品口径，
 * 否则极易被误解为「接了 Nacos 就该自动生效」。
 *
 * <p><b>三条实测结论（本轮踩坑，勿重踩）</b>：
 * <ol>
 *   <li><b>刷新 = 重建整个 Environment 并重跑一遍 config-data 导入</b>。
 *       SC 5 走的是 {@code ConfigDataContextRefresher}，不是当年的 {@code LegacyContextRefresher}。</li>
 *   <li><b>因此 {@code spring.config.import} 在刷新时也必须可见</b>，且
 *       alibaba 形态下还必须含 {@code nacos:} 条目——
 *       缺了会在刷新阶段抛 {@code No spring.config.import set} /
 *       {@code spring.config.import missing nacos:}，而<b>启动却完全正常</b>，极具迷惑性。</li>
 *   <li><b>测试框架注入的属性（{@code @SpringBootTest(properties=...)} /
 *       手塞 {@code MapPropertySource}）不进刷新后的环境</b>，用它们模拟「配置变了」是假绿。
 *       故本测试改用 {@code SpringApplicationBuilder} + 真实被导入的文件，走与 nacos: 同款的
 *       config-data 链路。</li>
 * </ol>
 */
class ConfigRefreshMechanismTest {

    @Test
    @DisplayName("刷新后 @ConfigurationProperties 重新绑定、@RefreshScope 重建；普通单例不变")
    void refresh_rebinds_configuration_properties_only() throws Exception {
        Path probeFile = Files.createTempFile("pivotos-refresh-probe", ".yaml");
        probeFile.toFile().deleteOnExit();
        Files.writeString(probeFile, probe("unused-file-baseline", "origin"), StandardCharsets.UTF_8);
        System.setProperty("pivotos.refresh.probe.value", "before");

        try (ConfigurableApplicationContext ctx = start(probeFile)) {
            RefreshProbeProperties properties = ctx.getBean(RefreshProbeProperties.class);
            ScopedProbe scopedProbe = ctx.getBean(ScopedProbe.class);
            PlainSingletonProbe singleton = ctx.getBean(PlainSingletonProbe.class);

            assertThat(properties.getValue()).isEqualTo("before");
            assertThat(scopedProbe.value()).isEqualTo("before");
            assertThat(singleton.value()).isEqualTo("before");

            assertThat(scopedProbe.value()).isEqualTo("before");

            System.setProperty("pivotos.refresh.probe.value", "after");
            ctx.publishEvent(new RefreshEvent(this, "test", "手动触发刷新"));

            assertThat(properties.getValue())
                .as("@ConfigurationProperties 必须拿到新值——「改配置不重启」的真正落点")
                .isEqualTo("after");
            assertThat(scopedProbe.value())
                .as("@RefreshScope Bean 是构造时快照：值变了就证明实例被重建过")
                .isEqualTo("after");
            assertThat(singleton.value())
                .as("普通单例的字段必须原地不变：不标 @RefreshScope 就是不生效")
                .isEqualTo("before");
        }
    }

    @Test
    @DisplayName("多次刷新连续生效（排除「只有第一次灵」的巧合）")
    void repeated_refresh_keeps_working() throws Exception {
        Path probeFile = Files.createTempFile("pivotos-refresh-probe", ".yaml");
        probeFile.toFile().deleteOnExit();
        System.setProperty("pivotos.refresh.probe.value", "round-0");

        try (ConfigurableApplicationContext ctx = start(probeFile)) {
            RefreshProbeProperties properties = ctx.getBean(RefreshProbeProperties.class);

            System.setProperty("pivotos.refresh.probe.value", "round-1");
            ctx.publishEvent(new RefreshEvent(this, "test", "刷新-1"));
            assertThat(properties.getValue()).isEqualTo("round-1");

            System.setProperty("pivotos.refresh.probe.value", "round-2");
            ctx.publishEvent(new RefreshEvent(this, "test", "刷新-2"));
            assertThat(properties.getValue()).isEqualTo("round-2");
        }
    }

    private ConfigurableApplicationContext start(Path probeFile) {
        return new SpringApplicationBuilder(RefreshMechanismApp.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "spring.application.name=pivotos-refresh-offline-test",
                // Nacos 不可达必须立刻失败：每次刷新都会重跑一遍导入
                "spring.cloud.nacos.config.server-addr=127.0.0.1:1",
                "spring.cloud.nacos.config.timeout=1000",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "logging.level.root=WARN",
                // 真正提供「可变配置」的来源：换回 optional:nacos: 即可切到真机，链路完全同款
                "spring.config.import[0]=optional:file:" + probeFile.toAbsolutePath(),
                // 第二条只为满足 SCA 的「import 里必须有 nacos: 条目」校验
                "spring.config.import[1]=optional:nacos:pivotos-refresh-offline-test.yaml")
            .run();
    }

    private static String probe(String value, String note) {
        return "pivotos:\n  refresh:\n    probe:\n      value: " + value + "\n      note: " + note + "\n";
    }

    @Data
    @ConfigurationProperties(prefix = "pivotos.refresh.probe")
    static class RefreshProbeProperties {
        private String value = "unset";
        private String note = "unset";
    }

    /** {@code @RefreshScope} 形态：刷新后重建实例 */
    @Configuration(proxyBeanMethods = false)
    static class ScopedProbeHolder {

        // proxyMode 必须显式给：不带代理的话，刷新只会把 Bean 从作用域里踢出去，
        // 调用方手上那个旧引用<b>照旧可用且永远不更新</b>——这是第 5 条坑，也是最阴的一条。
        @RefreshScope(proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
        @org.springframework.context.annotation.Bean
        ScopedProbe scopedProbe(org.springframework.core.env.Environment environment) {
            return new ScopedProbe(environment);
        }
    }

    static class ScopedProbe {
        /** 构造时快照：只有「实例真的被重建」这个值才会变（惰性读 Environment 会把两者混为一谈） */
        private final String snapshot;

        ScopedProbe(org.springframework.core.env.Environment environment) {
            this.snapshot = environment.getProperty("pivotos.refresh.probe.value");
        }

        String value() {
            return snapshot;
        }
    }

    /** 普通单例：不标任何刷新注解，用来做反证 */
    @Configuration(proxyBeanMethods = false)
    static class PlainProbeHolder {

        @org.springframework.context.annotation.Bean
        PlainSingletonProbe plainSingletonProbe(org.springframework.core.env.Environment environment) {
            return new PlainSingletonProbe(environment.getProperty("pivotos.refresh.probe.value"));
        }
    }

    static class PlainSingletonProbe {
        private final String snapshot;

        PlainSingletonProbe(String snapshot) {
            this.snapshot = snapshot;
        }

        String value() {
            return snapshot;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RefreshProbeProperties.class)
    @ImportAutoConfiguration({
        ConfigurationPropertiesAutoConfiguration.class,
        // 漏掉 ConfigurationPropertiesRebinderAutoConfiguration 就没有 ConfigurationPropertiesRebinder
        // Bean，@ConfigurationProperties 永远不会被重新绑定：刷新「成功」但值一直是旧的。
        // 这是本轮实测抓到的第 4 条坑——alibaba 形态必须显式确认它已装配。
        org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration.class,
        RefreshAutoConfiguration.class
    })
    @Import({ScopedProbeHolder.class, PlainProbeHolder.class})
    static class RefreshMechanismApp {
    }
}

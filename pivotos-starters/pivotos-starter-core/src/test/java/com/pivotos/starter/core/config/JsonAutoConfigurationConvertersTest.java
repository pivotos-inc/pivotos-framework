package com.pivotos.starter.core.config;

import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局序列化配置的「不劣化」守卫。
 *
 * <p><b>本轮实测抓到的潜伏缺陷</b>：{@code FastJsonMvcConfiguration} 原先用
 * {@code configureMessageConverters} 往列表里 add fastjson 转换器 —— 按
 * {@code WebMvcConfigurationSupport} 的语义，configure 阶段列表只要非空就<b>不再补默认转换器</b>，
 * 于是生效转换器从十来个掉到「只剩 fastjson 一个」：
 * 任何返回 {@code String} / {@code byte[]} / {@code Resource} 的端点都会 500
 * {@code HttpMessageNotWritableException}。产品侧当前没有这类端点（都返回 {@code R<?>}），
 * 所以一直没暴露；但凡加一个文件下载或文本端点就会踩。
 *
 * <p>修法是改成 {@code extendMessageConverters}（fastjson 仍在最前，默认转换器不再被挤掉）。
 * 本用例就是这条修法的事前/事后双向实证：改成 configure 会红，extend 才绿。
 */
class JsonAutoConfigurationConvertersTest {

    private static List<HttpMessageConverter<?>> convertersOf(String... properties) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
            .web(WebApplicationType.SERVLET)
            .properties(properties)
            .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.org.springframework=WARN")
            .run()) {
            return context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
        }
    }

    @Test
    void fastjson优先且默认转换器不被挤掉() {
        List<HttpMessageConverter<?>> converters = convertersOf();

        assertThat(converters).as("不能只剩 fastjson 一个转换器（configure 写法会把默认值整批挤掉）")
            .hasSizeGreaterThan(1);
        assertThat(converters.get(0)).as("fastjson 必须仍是最高优先级，否则序列化口径会变")
            .isInstanceOf(FastJsonHttpMessageConverter.class);
        assertThat(converters).as("String 转换器缺失会让所有文本端点 500")
            .anyMatch(StringHttpMessageConverter.class::isInstance);
    }

    @Test
    void 引擎切换jackson时不注册fastjson转换器() {
        List<HttpMessageConverter<?>> converters = convertersOf("pivotos.json.engine=jackson");

        assertThat(converters).as("engine=jackson 时 fastjson 转换器应整体回退")
            .noneMatch(FastJsonHttpMessageConverter.class::isInstance);
        assertThat(converters).anyMatch(StringHttpMessageConverter.class::isInstance);
    }

    @SpringBootApplication
    static class TestApp {
    }
}

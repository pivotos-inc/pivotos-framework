package com.pivotos.starter.core.config;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.support.config.FastJsonConfig;
import com.alibaba.fastjson2.support.spring6.http.converter.FastJsonHttpMessageConverter;
import com.pivotos.starter.core.config.properties.JsonProperties;
import com.pivotos.starter.core.json.SensitiveValueFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * fastjson2 全局序列化配置：
 * 时间格式统一 / Long 转 String 防前端精度丢失 / @Sensitive 脱敏。
 * pivotos.json.engine=jackson 时整体回退，本配置不生效。
 */
@AutoConfiguration
@EnableConfigurationProperties(JsonProperties.class)
public class JsonAutoConfiguration {

    /**
     * fastjson2 消息转换器注册（仅 Servlet Web 且引擎为 fastjson2 时生效）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "pivotos.json", name = "engine", havingValue = "fastjson2", matchIfMissing = true)
    static class FastJsonMvcConfiguration implements WebMvcConfigurer {

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            FastJsonConfig config = new FastJsonConfig();
            config.setCharset(StandardCharsets.UTF_8);
            config.setDateFormat("yyyy-MM-dd HH:mm:ss");
            // 安全纪律：严禁开启 AutoType，此处只配置写入侧特性
            config.setWriterFeatures(JSONWriter.Feature.WriteLongAsString);
            config.setWriterFilters(new SensitiveValueFilter());

            FastJsonHttpMessageConverter converter = new FastJsonHttpMessageConverter();
            converter.setFastJsonConfig(config);
            converter.setSupportedMediaTypes(List.of(
                    MediaType.APPLICATION_JSON,
                    new MediaType("application", "*+json")));
            converters.add(0, converter);
        }
    }
}

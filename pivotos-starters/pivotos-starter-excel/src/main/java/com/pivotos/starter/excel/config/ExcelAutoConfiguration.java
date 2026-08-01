package com.pivotos.starter.excel.config;

import com.pivotos.starter.excel.translator.DictTranslator;
import com.pivotos.starter.excel.util.ExcelHelper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Excel 导入导出自动装配：条件 = pivotos.excel.enabled=true 且存在 DictTranslator 实现。
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@AutoConfiguration
@ConditionalOnClass(com.alibaba.excel.EasyExcel.class)
@ConditionalOnProperty(prefix = "pivotos.excel", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ExcelProperties.class)
public class ExcelAutoConfiguration {

    /**
     * 查找 Spring 容器中唯一的 {@link DictTranslator} 实现，注入 ExcelHelper。
     * 业务模块（如 pivotos-plugin-system）负责提供 {@link DictTranslator} Bean。
     */
    @Bean
    @ConditionalOnMissingBean
    public ExcelHelper excelHelper(ApplicationContext context, ExcelProperties properties) {
        DictTranslator translator = context.getBeanProvider(DictTranslator.class)
                .getIfUnique();
        if (translator == null) {
            throw new IllegalStateException(
                    "未找到 DictTranslator Bean，请确保业务模块（如 plugin-system）提供了 DictTranslator 实现。"
                    + " 若不需要 Excel 功能，请设置 pivotos.excel.enabled=false。");
        }
        return new ExcelHelper(translator, properties);
    }
}

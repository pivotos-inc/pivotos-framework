package com.pivotos.workflow.config;

import com.pivotos.workflow.support.LegacyDesignerGuardFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * D1 旧内置设计器退役守卫的装配（v2.14.0）。
 * <p>
 * 开关 {@code pivotos.workflow.legacy-designer.enabled}：
 * <ul>
 *   <li>缺省 / false —— 注册守卫，旧入口 {@code /warm-flow-ui} 全部返回 410（默认口径：已下线）；</li>
 *   <li>true —— 不注册守卫，旧入口恢复可用（并存态，回滚兜底）。</li>
 * </ul>
 * 用 {@code matchIfMissing = true} 表达「不配即下线」，避免遗漏配置导致下线失效。
 */
@Configuration(proxyBeanMethods = false)
public class LegacyDesignerGuardConfig {

    /** 旧内置设计器 UI 路径前缀（与引擎 jar 的挂载点一致） */
    private static final String LEGACY_UI_PATH = "/warm-flow-ui";

    @Bean
    @ConditionalOnProperty(prefix = "pivotos.workflow.legacy-designer", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public FilterRegistrationBean<LegacyDesignerGuardFilter> legacyDesignerGuardFilter() {
        FilterRegistrationBean<LegacyDesignerGuardFilter> registration =
                new FilterRegistrationBean<>(new LegacyDesignerGuardFilter());
        // 同时覆盖 /warm-flow-ui 与 /warm-flow-ui/**（含 index.html 与 /config 等 UI 接口）
        registration.addUrlPatterns(LEGACY_UI_PATH, LEGACY_UI_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("legacyDesignerGuardFilter");
        return registration;
    }
}

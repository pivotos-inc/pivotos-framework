package com.pivotos.cloud.seata;

import com.pivotos.cloud.openfeign.CloudOpenFeignAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * S133 V3 spike —— Cloud Starter ⑤：分布式事务边界（对齐 Seata 的 XID 传播语义）。
 *
 * <p>spike 期只做「事务上下文生成与传播」这一件可验证的事：请求进入 → 生成 XID 写入
 * {@link CloudOpenFeignAutoConfiguration.TxContext} → 跨 Facade 的远程调用自动携带 {@code X-Tx-Id}
 * → 响应结束清理。真正的两阶段提交/回滚round在该抽象之上另行实现。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "pivotos.cloud.seata", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "com.pivotos.cloud.openfeign.CloudOpenFeignAutoConfiguration")
public class SeataAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SeataAutoConfiguration.class);

    /** 显式 Environment 绑定（Boot 4.1.0 下 binder 对命令行参数失效，见 openfeign 同注释） */
    @Bean
    public SeataProps seataProps(org.springframework.core.env.Environment env) {
        SeataProps p = new SeataProps();
        String prefix = "pivotos.cloud.seata.";
        p.setEnabled(env.getProperty(prefix + "enabled", Boolean.class, false));
        p.setTimeoutMs(env.getProperty(prefix + "timeout-ms", Long.class, 30000L));
        p.setTxGroup(env.getProperty(prefix + "tx-group", "PIVOTOS_TX_GROUP"));
        return p;
    }

    @ConfigurationProperties(prefix = "pivotos.cloud.seata")
    public static class SeataProps {
        private boolean enabled = false;
        /** 事务上下文超时（毫秒），仅做记录 */
        private long timeoutMs = 30000;
        private String txGroup = "PIVOTOS_TX_GROUP";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getTxGroup() {
            return txGroup;
        }

        public void setTxGroup(String txGroup) {
            this.txGroup = txGroup;
        }
    }

    @Bean
    public FilterRegistrationBean<Filter> cloudSeataTxBoundaryFilter(SeataProps props) {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(new TxBoundaryFilter(props));
        bean.setName("pivotos-cloud-seata-tx");
        bean.addUrlPatterns("/*");
        bean.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE + 5);
        log.info("[CLOUD][seata] 事务边界已装配：group={}, timeout={}ms", props.getTxGroup(), props.getTimeoutMs());
        return bean;
    }

    static class TxBoundaryFilter implements Filter {
        private final SeataProps props;

        TxBoundaryFilter(SeataProps props) {
            this.props = props;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String xid = props.getTxGroup() + ":" + UUID.randomUUID().toString().substring(0, 12);
            CloudOpenFeignAutoConfiguration.TxContext.begin(xid);
            try {
                chain.doFilter(request, response);
            } finally {
                CloudOpenFeignAutoConfiguration.TxContext.clear();
            }
        }
    }

    /** 调试用：打印当前事务上下文（便于脚本断言 XID 是否被传播到下游） */
    static Map<String, String> currentTx() {
        return Map.of("xid", String.valueOf(CloudOpenFeignAutoConfiguration.TxContext.currentId()));
    }
}

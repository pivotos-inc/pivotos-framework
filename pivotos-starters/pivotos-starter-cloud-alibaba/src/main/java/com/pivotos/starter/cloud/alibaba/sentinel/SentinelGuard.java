package com.pivotos.starter.cloud.alibaba.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.pivotos.starter.cloud.alibaba.config.AlibabaCloudProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sentinel 保护门面：把「资源 + 阈值」收敛成一处，避免业务代码散落 {@code SphU} 调用。
 *
 * <p>规则来源只有配置（{@code pivotos.cloud.alibaba.rules}）——
 * 不读 dashboard、不连控制台：Sentinel 的规则加载是<b>本地</b>能力，
 * 因此即便没有任何外部服务，限流也能真实验证（见 SentinelGuardTest）。
 */
public class SentinelGuard {

    private final AlibabaCloudProperties properties;

    public SentinelGuard(AlibabaCloudProperties properties) {
        this.properties = properties;
        loadRules();
    }

    /** 加载流量规则（QPS 阈值，阈值 <= 0 的条目跳过） */
    public void loadRules() {
        List<FlowRule> rules = new ArrayList<>();
        Map<String, Integer> configured = properties.getRules();
        if (configured != null) {
            configured.forEach((resource, qps) -> {
                if (resource == null || resource.isBlank() || qps == null || qps <= 0) {
                    return;
                }
                FlowRule rule = new FlowRule(resource(resource));
                rule.setCount(qps.doubleValue());
                rule.setGrade(com.alibaba.csp.sentinel.slots.block.RuleConstant.FLOW_GRADE_QPS);
                rules.add(rule);
            });
        }
        if (!rules.isEmpty()) {
            FlowRuleManager.loadRules(rules);
        }
    }

    /** 资源名（带前缀） */
    public String resource(String name) {
        String prefix = properties.getResourcePrefix();
        return prefix == null || prefix.isBlank() ? name : prefix + ":" + name;
    }

    public boolean isEnabled() {
        return properties.isSentinelEnabled();
    }

    /**
     * 受保护执行：超阈值抛 {@link IllegalStateException}（携带资源名，便于日志定位）；
     * 未启用 Sentinel 时直通。
     *
     * <p>选择抛运行时异常而不是返回默认值：限流「静默降级」会让调用方以为成功，
     * 在写操作上是错的；是否降级应由调用方显式决定。
     */
    public <T> T protect(String name, Supplier<T> supplier) {
        if (!isEnabled()) {
            return supplier.get();
        }
        String resource = resource(name);
        try (Entry ignored = SphU.entry(resource)) {
            return supplier.get();
        } catch (BlockException e) {
            throw new IllegalStateException("Sentinel 限流生效，资源=" + resource);
        }
    }
}

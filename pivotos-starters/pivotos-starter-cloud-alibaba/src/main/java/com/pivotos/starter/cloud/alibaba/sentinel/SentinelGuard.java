package com.pivotos.starter.cloud.alibaba.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.pivotos.starter.cloud.alibaba.config.AlibabaCloudProperties;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
            configured.forEach((raw, qps) -> {
                if (raw == null || raw.isBlank() || qps == null || qps <= 0) {
                    return;
                }
                String resource = resource(raw);
                FlowRule rule = new FlowRule(resource);
                rule.setCount(qps.doubleValue());
                rule.setGrade(com.alibaba.csp.sentinel.slots.block.RuleConstant.FLOW_GRADE_QPS);
                rules.add(rule);
                // 必须打印**实际生效的资源名**：只打「规则 N 条」看不出拼了前缀没有，
                // 本次真机就栽在这——日志说规则 1 条，实际资源名拼成 pivotos:/system/auth/login，
                // 与 Sentinel WebMvc 拦截器用的 URI 资源名对不上，限流静默失效。
                log.info("[CLOUD][alibaba] Sentinel 流控规则：{} -> QPS {}", resource, qps);
            });
        }
        if (!rules.isEmpty()) {
            FlowRuleManager.loadRules(rules);
        }
    }

    /**
     * 资源名。
     *
     * <p><b>以 {@code /} 开头视为 Web URI，一律不拼前缀</b>：Sentinel WebMvc 拦截器
     * （SCA 的 SentinelWebInterceptor）使用的资源名就是 URI 本身，拼上前缀后规则永远匹配不上，
     * Web 侧限流会静默失效（真机实测：3 次连打全部 200，block 日志为空）。
     * 非 URI 形态（业务资源名）才拼 {@code resourcePrefix}，避免与框架自带资源撞名。
     */
    public String resource(String name) {
        String prefix = properties.getResourcePrefix();
        if (prefix == null || prefix.isBlank() || name.startsWith("/")) {
            return name;
        }
        return prefix + ":" + name;
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

package com.pivotos.starter.job.retention;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 本地兜底的生效条件：<strong>（未装配 XXL-Job）或（显式 force）</strong>。
 *
 * <h3>为什么不能只用 {@code @ConditionalOnMissingBean(XxlJobSpringExecutor)}</h3>
 * 那个条件判的是「<strong>装配</strong>」，而 B1 要兜的是「<strong>不可达</strong>」——两件事。
 * 实测 dev：<code>pivotos.job.admin-addresses: http://127.0.0.1:8101</code> 已配，
 * 于是 {@code XxlJobSpringExecutor} 照样装配，而 8101 上根本没有调度中心
 * （启动日志 <code>xxl-job registry error</code>）。此时：
 * <ul>
 *   <li>按 missing-bean 条件 → 兜底<strong>不注册</strong>；</li>
 *   <li>实际效果 → 调度中心挂了，留存清理跟着<strong>静默停止</strong>，
 *       而「清理没跑」这件事没有任何接口或端点能看出来。</li>
 * </ul>
 * 装配与否是启动期可知的，可达与否只有运行期才知道——一个启动期条件没法覆盖后者。
 * 故补一个显式开关：运维确认中心长期不可达时，用
 * {@code pivotos.retention.local-fallback.force=true} 强制启用本地调度（需重启）。
 *
 * <h3>代价说清楚</h3>
 * {@code force=true} 且中心其实可达时，清理会被跑两遍（调度中心一次、本地一次）。
 * 清理按主键幂等（第二轮 matched=0），结果正确，但执行记录会多一条。
 * 这是显式开关，代价由开启者承担，默认关闭。
 *
 * @author PivotOS
 * @since 3.0.0
 */
public class LocalFallbackCondition extends SpringBootCondition {

    static final String FORCE_KEY = "pivotos.retention.local-fallback.force";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean force = Boolean.TRUE.equals(
                context.getEnvironment().getProperty(FORCE_KEY, Boolean.class, Boolean.FALSE));
        boolean xxlPresent = context.getBeanFactory() != null
                && context.getBeanFactory()
                        .getBeanNamesForType(XxlJobSpringExecutor.class, true, false).length > 0;

        if (xxlPresent && !force) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition(
                            "pivotos.retention.local-fallback")
                    .because("已装配 XxlJobSpringExecutor（调度中心已配置），本地兜底不接管；"
                            + "若调度中心实际不可达，用 " + FORCE_KEY + "=true 强制启用"));
        }
        return ConditionOutcome.match(ConditionMessage.forCondition("pivotos.retention.local-fallback")
                .because(force ? FORCE_KEY + "=true（显式强制）"
                        : "未装配 XxlJobSpringExecutor，由本地调度接管"));
    }
}

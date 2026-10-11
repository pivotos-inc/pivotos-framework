package com.pivotos.starter.job.retention;

import com.pivotos.starter.job.api.JobHandlerInfo;
import com.pivotos.starter.job.config.JobAutoConfiguration;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 留存清理的本地调度兜底：XXL-Job 调度中心不可达时，靠 Spring {@code @Scheduled} 仍然执行。
 * <p>
 * <b>为什么不会与 XXL-Job 双跑</b>：本类以
 * {@code @ConditionalOnMissingBean(XxlJobSpringExecutor.class)} 守卫——
 * {@code XxlJobSpringExecutor} 存在即说明调度中心已装配（{@code pivotos.job.admin-addresses} 已配），
 * 此时本类<strong>整个不注册</strong>，本地一次都不跑；反之才接管。
 * 用类型存在性而不是配置纪律来互斥，改配置不会破。
 * <p>
 * <b>为什么 {@code @EnableScheduling} 挂在这里而不是全局</b>：全仓此前零 {@code @Scheduled}，
 * 全局开启等于给未来所有 Bean 打开定时能力；挂在本类上则是「只有兜底生效时才启用调度」。
 * <p>
 * <b>已知边界</b>：多副本部署时每个副本都会各跑一次（本兜底没有分布式锁）。
 * 清理操作按主键幂等（重复执行只是第二轮 matched=0），故可接受；
 * 正式环境建议配 XXL-Job 或只让一个副本开启本兜底。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@AutoConfiguration
@AutoConfigureAfter(JobAutoConfiguration.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "pivotos.retention.local-fallback", name = "enabled", matchIfMissing = true)
// 注意：不是简单的 @ConditionalOnMissingBean(XxlJobSpringExecutor)——那只判「装配」，
// B1 要兜的是「不可达」。详见 LocalFallbackCondition。
@Conditional(LocalFallbackCondition.class)
public class LocalRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalRetentionScheduler.class);

    private final RetentionJobRegistry registry;

    private final RetentionProperties properties;

    /** 与 @Scheduled 同一个占位符：启动时打印出来，运维才知道下一次几点跑 */
    @org.springframework.beans.factory.annotation.Value(
            "${pivotos.retention.local-fallback.cron:0 0 3 * * *}")
    private String cron;

    public LocalRetentionScheduler(RetentionJobRegistry registry, RetentionProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 启动完成后自检并明说接管了什么。
     * <p>
     * 为什么必须有这条：本类是「XXL-Job 缺席时才出现」的兜底，
     * 它生效与否在接口层完全看不出来（没有任何端点暴露它）。若不在启动日志里说清楚，
     * 运维遇到「调度中心挂了、清理也就静默停了」时无从判断兜底有没有接管。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reportRegisteredJobs() {
        List<JobHandlerInfo> jobs = registry.list();
        log.info("[Retention] 本地调度兜底已接管（{}）：cron={}，已登记 {} 个留存任务",
                properties.getLocalFallback().isForce()
                        ? "force=true 强制接管，注意可能与调度中心双跑"
                        : "未装配 XXL-Job",
                cron, jobs.size());
        for (JobHandlerInfo job : jobs) {
            log.info("[Retention]   · {} {}", job.handler(), job.displayName());
        }
        if (jobs.isEmpty()) {
            log.warn("[Retention] 没有任何留存任务登记——留存清理不会执行（相关插件未引入？）");
        }
    }

    /**
     * 依次执行全部已登记的留存清理任务。
     * <p><b>单任务失败不影响后续</b>：某个表的清理抛异常（如该表被锁）不该让其它表跟着不清理，
     * 故逐个 try/catch 并记 ERROR——失败会留在日志里，不会静默消失。
     */
    @Scheduled(cron = "${pivotos.retention.local-fallback.cron:0 0 3 * * *}")
    public void runRetentionJobs() {
        List<JobHandlerInfo> jobs = registry.list();
        if (jobs.isEmpty()) {
            log.info("[Retention] 本地兜底调度触发，但没有任何留存任务登记（正常：相关插件未引入）");
            return;
        }
        log.info("[Retention] 本地兜底调度开始：{} 个任务", jobs.size());
        for (JobHandlerInfo job : jobs) {
            try {
                registry.find(job.handler()).ifPresentOrElse(
                        Runnable::run,
                        () -> log.warn("[Retention] 任务 {} 已登记但查不到执行体（跳过）", job.handler()));
            } catch (RuntimeException e) {
                log.error("[Retention] 本地兜底任务 {} 执行失败，继续下一个", job.handler(), e);
            }
        }
        log.info("[Retention] 本地兜底调度结束");
    }
}

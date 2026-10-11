package com.pivotos.ai.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.domain.entity.AiChatMessage;
import com.pivotos.ai.domain.entity.AiToolInvoke;
import com.pivotos.ai.domain.entity.AiUsage;
import com.pivotos.ai.mapper.AiChatMessageMapper;
import com.pivotos.ai.mapper.AiToolInvokeMapper;
import com.pivotos.ai.mapper.AiUsageMapper;
import com.pivotos.starter.job.api.JobExecutionHelper;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.pivotos.starter.job.retention.RetentionCleanupTemplate;
import com.pivotos.starter.job.retention.RetentionIdBatch;
import com.pivotos.starter.job.retention.RetentionJobRegistry;
import com.pivotos.starter.job.retention.RetentionProperties;
import com.pivotos.starter.job.retention.RetentionPurgeOps;
import com.pivotos.starter.job.retention.RetentionReport;
import com.pivotos.starter.job.retention.TenantIdProvider;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * AI 三张流水表留存清理（v3.1.0 生产化 P1-B1）：
 * {@code ai_chat_message} / {@code ai_usage} / {@code ai_tool_invoke}。
 * <p>
 * <b>前两张与第三张的口径刻意不同，别合并</b>：
 * <ul>
 *   <li>{@code ai_chat_message}、{@code ai_tool_invoke}：<strong>逐租户清理</strong>。
 *       两表 Mapper 无 {@code @InterceptorIgnore}，走默认租户行级过滤；
 *       且租户上下文为空时 {@code ColumnTenantStrategy#ignoreTable} 一律放行，
 *       不逐租户就是跨租户全表删除。</li>
 *   <li>{@code ai_usage}：<strong>全局清理</strong>。该表 Mapper 全部方法
 *       {@code @InterceptorIgnore(tenantLine = "true")}（写入来自无租户上下文的回调线程、
 *       聚合是平台跨租户视角），留存清理沿用同一口径，否则会漏掉 {@code tenant_id IS NULL} 的行。</li>
 * </ul>
 * <p>
 * <b>刻意不清理 {@code ai_kb_chunk} / {@code ai_kb_document}</b>：知识库切片是业务资产不是流水，
 * 删了等于丢知识——留存策略只管「可再生的流水」，不管「不可再生的资产」。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class AiRetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiRetentionCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "aiRetentionCleanup";

    private final AiChatMessageMapper chatMessageMapper;
    private final AiUsageMapper usageMapper;
    private final AiToolInvokeMapper toolInvokeMapper;
    private final RetentionProperties properties;
    private final ObjectProvider<TenantIdProvider> tenantProvider;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;

    public AiRetentionCleanupJob(AiChatMessageMapper chatMessageMapper,
                                 AiUsageMapper usageMapper,
                                 AiToolInvokeMapper toolInvokeMapper,
                                 RetentionProperties properties,
                                 ObjectProvider<TenantIdProvider> tenantProvider,
                                 ObjectProvider<JobExecutionRecorder> recorder,
                                 JobHandlerRegistry registry,
                                 RetentionJobRegistry retentionRegistry) {
        this.chatMessageMapper = chatMessageMapper;
        this.usageMapper = usageMapper;
        this.toolInvokeMapper = toolInvokeMapper;
        this.properties = properties;
        this.tenantProvider = tenantProvider;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "AI 流水留存清理", this::execute);
        retentionRegistry.register(HANDLER, "AI 流水留存清理", this::execute);
    }

    /**
     * AI 流水留存清理（建议 cron: 0 30 3 * * ?）。
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        TenantIdProvider tenants = tenantProvider.getIfAvailable();

        int chatDays = properties.getAiChatDays();
        LocalDateTime chatCutoff = LocalDateTime.now().minusDays(chatDays);
        RetentionReport chatReport = RetentionCleanupTemplate.runPerTenant(properties, "ai_chat_message",
                chatDays, tenants, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<AiChatMessage> query = new LambdaQueryWrapper<>();
                        query.lt(AiChatMessage::getCreateTime, chatCutoff).select(AiChatMessage::getId);
                        Page<AiChatMessage> page = chatMessageMapper.selectPage(new Page<>(1, size), query);
                        return new RetentionIdBatch(page.getRecords().stream()
                                .map(AiChatMessage::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return chatMessageMapper.physicalDeleteByIds(ids);
                    }
                });
        log.info("[Job] {}", chatReport.summary());

        int toolDays = properties.getAiToolInvokeDays();
        LocalDateTime toolCutoff = LocalDateTime.now().minusDays(toolDays);
        RetentionReport toolReport = RetentionCleanupTemplate.runPerTenant(properties, "ai_tool_invoke",
                toolDays, tenants, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<AiToolInvoke> query = new LambdaQueryWrapper<>();
                        query.lt(AiToolInvoke::getCreateTime, toolCutoff).select(AiToolInvoke::getId);
                        Page<AiToolInvoke> page = toolInvokeMapper.selectPage(new Page<>(1, size), query);
                        return new RetentionIdBatch(page.getRecords().stream()
                                .map(AiToolInvoke::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return toolInvokeMapper.physicalDeleteByIds(ids);
                    }
                });
        log.info("[Job] {}", toolReport.summary());

        int usageDays = properties.getAiUsageDays();
        LocalDateTime usageCutoff = LocalDateTime.now().minusDays(usageDays);
        RetentionReport usageReport = RetentionCleanupTemplate.runGlobal(properties, "ai_usage",
                usageDays, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<AiUsage> query = new LambdaQueryWrapper<>();
                        query.lt(AiUsage::getCreateTime, usageCutoff).select(AiUsage::getId);
                        Page<AiUsage> page = usageMapper.selectPage(new Page<>(1, size), query);
                        return new RetentionIdBatch(page.getRecords().stream()
                                .map(AiUsage::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return usageMapper.physicalDeleteByIds(ids);
                    }
                });
        log.info("[Job] {}", usageReport.summary());
    }
}

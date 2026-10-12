package com.pivotos.message.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.message.domain.entity.MsgMessage;
import com.pivotos.message.domain.entity.MsgSendLog;
import com.pivotos.message.domain.entity.MsgUserMessage;
import com.pivotos.message.mapper.MsgMessageMapper;
import com.pivotos.message.mapper.MsgSendLogMapper;
import com.pivotos.message.mapper.MsgUserMessageMapper;
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
 * 消息三表留存清理（v3.1.0 生产化 P1-B1）：
 * {@code msg_message} / {@code msg_user_message} / {@code msg_send_log}。
 * <p>
 * <b>这三张表都带 tenant_id，必须逐租户执行</b>——原因写在
 * {@link RetentionCleanupTemplate#runPerTenant}：租户上下文为空时
 * {@code ColumnTenantStrategy#ignoreTable} 一律放行，一条 DELETE 就是跨租户全表删除且不报错。
 * <p>
 * <b>已知边界</b>：实体注释写着「租户 ID 多租户预留，当前由代码置 null」，
 * 即 {@code tenant_id IS NULL} 的行不在任何租户的清理范围内。这是刻意的选择——
 * 不去猜这些无主行的归属，宁可留着；真要清理需先明确归属口径。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Component
public class MessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MessageCleanupJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "messageRetentionCleanup";

    private final MsgMessageMapper messageMapper;
    private final MsgUserMessageMapper userMessageMapper;
    private final MsgSendLogMapper sendLogMapper;
    private final RetentionProperties properties;
    private final ObjectProvider<TenantIdProvider> tenantProvider;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;
    private final RetentionJobRegistry retentionRegistry;

    public MessageCleanupJob(MsgMessageMapper messageMapper,
                             MsgUserMessageMapper userMessageMapper,
                             MsgSendLogMapper sendLogMapper,
                             RetentionProperties properties,
                             ObjectProvider<TenantIdProvider> tenantProvider,
                             ObjectProvider<JobExecutionRecorder> recorder,
                             JobHandlerRegistry registry,
                             RetentionJobRegistry retentionRegistry) {
        this.messageMapper = messageMapper;
        this.userMessageMapper = userMessageMapper;
        this.sendLogMapper = sendLogMapper;
        this.properties = properties;
        this.tenantProvider = tenantProvider;
        this.recorder = recorder;
        this.registry = registry;
        this.retentionRegistry = retentionRegistry;
    }

    /** 自注册：手动触发 + 本地兜底调度 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, "消息留存清理", this::execute);
        retentionRegistry.register(HANDLER, "消息留存清理", this::execute);
    }

    /**
     * 消息留存清理（建议 cron: 0 20 3 * * ?）。
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        int days = properties.getMessageDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        // 租户枚举由 plugin-system 提供；未引入时为空 → 逐租户清理退化为 0 租户（不误删）
        TenantIdProvider tenants = tenantProvider.getIfAvailable();

        log.info("[Job] {}", RetentionCleanupTemplate.runPerTenant(properties, "msg_message", days,
                tenants, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<MsgMessage> query = new LambdaQueryWrapper<>();
                        query.lt(MsgMessage::getCreateTime, cutoff).select(MsgMessage::getId);
                        Page<MsgMessage> page = messageMapper.selectPage(new Page<>(1, size), query);
                        return toBatch(page.getRecords().stream()
                                .map(MsgMessage::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return messageMapper.physicalDeleteByIds(ids);
                    }
                }).summary());

        log.info("[Job] {}", RetentionCleanupTemplate.runPerTenant(properties, "msg_user_message", days,
                tenants, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<MsgUserMessage> query = new LambdaQueryWrapper<>();
                        query.lt(MsgUserMessage::getCreateTime, cutoff).select(MsgUserMessage::getId);
                        Page<MsgUserMessage> page = userMessageMapper.selectPage(new Page<>(1, size), query);
                        return toBatch(page.getRecords().stream()
                                .map(MsgUserMessage::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return userMessageMapper.physicalDeleteByIds(ids);
                    }
                }).summary());

        log.info("[Job] {}", RetentionCleanupTemplate.runPerTenant(properties, "msg_send_log", days,
                tenants, new RetentionPurgeOps() {
                    @Override
                    public RetentionIdBatch loadExpiredIds(int size) {
                        LambdaQueryWrapper<MsgSendLog> query = new LambdaQueryWrapper<>();
                        query.lt(MsgSendLog::getCreateTime, cutoff).select(MsgSendLog::getId);
                        Page<MsgSendLog> page = sendLogMapper.selectPage(new Page<>(1, size), query);
                        return toBatch(page.getRecords().stream()
                                .map(MsgSendLog::getId).filter(Objects::nonNull).toList(), page.getTotal());
                    }

                    @Override
                    public int deleteByIds(List<Long> ids) {
                        return sendLogMapper.physicalDeleteByIds(ids);
                    }
                }).summary());
    }

    private static RetentionIdBatch toBatch(List<Long> ids, long total) {
        return new RetentionIdBatch(ids, total);
    }
}

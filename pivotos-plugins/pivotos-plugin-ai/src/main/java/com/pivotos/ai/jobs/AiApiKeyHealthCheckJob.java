package com.pivotos.ai.jobs;

import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.vo.ProviderVO;
import com.pivotos.ai.service.AiProviderService;
import com.pivotos.starter.job.api.JobExecutionHelper;
import com.pivotos.starter.job.api.JobExecutionRecorder;
import com.pivotos.starter.job.api.JobHandlerRegistry;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI API Key health check scheduled job.
 * <p>
 * Runs every minute, scans all enabled providers' active keys,
 * probes each key and updates failure/success counters via AiProviderService.
 * <p>
 * When a key reaches the failure threshold, recordKeyFailure auto-disables
 * the key and sends an in-app notification.
 * <p>
 * 每次执行写入 sys_job_log（S37）；自注册到 JobHandlerRegistry 支持手动触发。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class AiApiKeyHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(AiApiKeyHealthCheckJob.class);

    /** XXL-Job handler 名 */
    public static final String HANDLER = "aiApiKeyHealthCheck";

    private final AiProviderService aiProviderService;
    private final ObjectProvider<JobExecutionRecorder> recorder;
    private final JobHandlerRegistry registry;

    public AiApiKeyHealthCheckJob(AiProviderService aiProviderService,
                                  ObjectProvider<JobExecutionRecorder> recorder,
                                  JobHandlerRegistry registry) {
        this.aiProviderService = aiProviderService;
        this.recorder = recorder;
        this.registry = registry;
    }

    /** 自注册：支持管理端手动触发 */
    @PostConstruct
    public void registerManualTrigger() {
        registry.register(HANDLER, this::execute);
    }

    /**
     * AI Key health check (suggested cron: 0 * /1 * * * ?).
     */
    @XxlJob(HANDLER)
    public void execute() {
        JobExecutionHelper.run(HANDLER, recorder, this::doExecute);
    }

    private void doExecute() {
        log.info("[Job] AI Key health check started");

        List<ProviderVO> providers = aiProviderService.listProviders();
        int totalKeys = 0;
        int successCount = 0;
        int failedCount = 0;

        for (ProviderVO provider : providers) {
            // status: 0=enabled, 1=disabled
            if (provider.getStatus() == null || provider.getStatus() != 0) {
                continue;
            }
            if (provider.getActiveKeyCount() == null || provider.getActiveKeyCount() == 0) {
                continue;
            }

            List<AiApiKey> activeKeys = aiProviderService.listActiveKeys(provider.getId());
            totalKeys += activeKeys.size();

            for (AiApiKey key : activeKeys) {
                try {
                    aiProviderService.listModels(provider.getId());
                    aiProviderService.recordKeySuccess(key.getId());
                    successCount++;
                } catch (Exception e) {
                    log.warn("[Job] Key health check failed: provider={}({}), keyId={}",
                            provider.getName(), provider.getCode(), key.getId());
                    aiProviderService.recordKeyFailure(key.getId());
                    failedCount++;
                }
            }
        }

        log.info("[Job] AI Key health check done: total={}, success={}, failed={}",
                totalKeys, successCount, failedCount);
    }
}

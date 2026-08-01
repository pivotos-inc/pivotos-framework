package com.pivotos.ai.jobs;

import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.vo.ProviderVO;
import com.pivotos.ai.service.AiProviderService;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Component
public class AiApiKeyHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(AiApiKeyHealthCheckJob.class);

    private final AiProviderService aiProviderService;

    public AiApiKeyHealthCheckJob(AiProviderService aiProviderService) {
        this.aiProviderService = aiProviderService;
    }

    /**
     * AI Key health check (suggested cron: 0 * /1 * * * ?).
     */
    @XxlJob("aiApiKeyHealthCheck")
    public void execute() {
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

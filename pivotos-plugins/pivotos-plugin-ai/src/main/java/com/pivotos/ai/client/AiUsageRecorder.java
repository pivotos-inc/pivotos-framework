package com.pivotos.ai.client;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.ai.domain.entity.AiUsage;
import com.pivotos.ai.mapper.AiUsageMapper;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI Token 用量记录器（S92）
 *
 * <p>调用线程先 {@link Snapshot#capture()} 抓取场景/用户/租户（流式回调线程无上下文），
 * 落库走虚拟线程异步执行：计量失败只 warn，绝不阻断业务链路（决策：不因计量阻断业务）。
 * 审计字段显式预填（回调线程无 LoginContext，自动填充不可依赖，同 saveMessage 口径）。
 */
@Component
@RequiredArgsConstructor
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private final AiUsageMapper usageMapper;

    /** 计量落库专用虚拟线程池：与业务请求隔离，insert 慢不拖对话链路 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 调用线程上下文快照（场景 + 登录人 + 租户） */
    public record Snapshot(String scene, Long userId, Long tenantId) {
        /** 在当前（调用）线程抓取；未登录/未绑定租户记 null */
        public static Snapshot capture() {
            return new Snapshot(AiUsageContext.getScene(), LoginContext.getUserId(), TenantContext.get());
        }
    }

    /**
     * 异步落一条用量记录。
     *
     * @param snapshot          调用线程快照
     * @param providerId        供应商 ID（静态兜底 null）
     * @param providerCode      供应商编码（静态兜底 static）
     * @param keyId             Key ID（静态兜底 null）
     * @param model             实际模型名
     * @param callType          chat / embedding
     * @param promptTokens      提示词 token（缺失记 0）
     * @param completionTokens  生成 token（缺失记 0）
     * @param failed            是否失败调用
     */
    public void record(Snapshot snapshot, Long providerId, String providerCode, Long keyId,
                       String model, String callType,
                       int promptTokens, int completionTokens, boolean failed) {
        executor.execute(() -> {
            try {
                LocalDateTime now = LocalDateTime.now();
                AiUsage usage = new AiUsage();
                usage.setId(IdWorker.getId());
                usage.setUserId(snapshot.userId());
                usage.setTenantId(snapshot.tenantId());
                usage.setProviderId(providerId);
                usage.setProviderCode(providerCode);
                usage.setKeyId(keyId);
                usage.setModel(model == null ? "" : model);
                usage.setScene(snapshot.scene());
                usage.setCallType(callType);
                usage.setPromptTokens(promptTokens);
                usage.setCompletionTokens(completionTokens);
                usage.setTotalTokens(promptTokens + completionTokens);
                usage.setFailed(failed ? 1 : 0);
                usage.setCreateBy(snapshot.userId());
                usage.setUpdateBy(snapshot.userId());
                usage.setCreateTime(now);
                usage.setUpdateTime(now);
                usageMapper.insertUsage(usage);
            } catch (Exception e) {
                log.warn("[PivotOS] AI 用量落库失败（不影响业务）：{}", e.getMessage());
            }
        });
    }
}

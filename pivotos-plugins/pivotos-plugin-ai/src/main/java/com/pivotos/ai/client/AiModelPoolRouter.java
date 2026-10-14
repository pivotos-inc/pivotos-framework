package com.pivotos.ai.client;

import com.pivotos.ai.domain.entity.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话模型池轮询器（V3.2-D · 免费额度轮转降本）。
 *
 * <h3>为什么需要它</h3>
 * 阿里云百炼「新人免费额度」是**按模型维度独立发放**的（通常每模型 100 万 Token），
 * 且官方明确：「当某个模型额度用完后，系统不会自动切换到其他有额度的模型，
 * 如需使用其他模型的免费额度，需在代码或工具配置中手动修改 model 参数」。
 * 也就是说——**想轮转免费额度，只能自己在应用层做**。
 *
 * <p>本类做的两件事：
 * <ol>
 *   <li><b>轮询</b>：把 {@code ai_provider.model_pool} 里的一批可互替模型依次分摊，
 *       每次调用推进一次游标（跨请求也分摊，不是「一次请求内才转」）。</li>
 *   <li><b>摘除</b>：识别百炼额度耗尽的 {@code 403 AllocationQuota.FreeTierOnly}，
 *       把该模型标记为耗尽并跳过，自动切下一个。</li>
 * </ol>
 *
 * <h3>为什么不复用 Key 的健康度机制</h3>
 * 现有 {@link AiClientRegistry#nextKeyIndex} + {@code recordKeyFailure} 是 **Key 维度**的：
 * 额度耗尽的锅在「模型」不在「Key」，若走 Key 失败计数会把好 Key 误停用
 * （{@code key-fail-threshold} 打满即停用 + 站内信告警），属于误伤。所以模型维度单独记账。
 *
 * <h3>降级终点</h3>
 * 池内全部耗尽时回落 {@code provider.defaultModel}（便宜档位，按量付费），
 * 保证服务不会因免费额度用尽而整体不可用。若管理员开了百炼的「免费额度用完即停」，
 * 则降级终点也会 403 —— 这是预期行为，宁可失败也不悄悄烧钱。
 *
 * <h3>状态是内存态</h3>
 * 耗尽标记不落库：额度是平台侧事实，重启后重新发现的成本只是「每个耗尽模型各失败一次」。
 * 为避免一次误判把模型永久摘掉，标记带 {@link #EXHAUSTED_TTL_MILLIS} 自动恢复。
 */
@Component
public class AiModelPoolRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelPoolRouter.class);

    /**
     * 百炼免费额度耗尽错误码：{@code HTTP 403 + AllocationQuota.FreeTierOnly}。
     * 官方口径：开启「免费额度用完即停」后，额度耗尽返回该错误码且不扣费。
     */
    public static final String FREE_TIER_EXHAUSTED_CODE = "AllocationQuota.FreeTierOnly";

    /** 耗尽标记自动恢复时间：12 小时（防误判永久摘除；重试一次的代价极低） */
    private static final long EXHAUSTED_TTL_MILLIS = 12 * 60 * 60 * 1000L;

    /** cause 链遍历深度上限（防自引用成环） */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** providerId → 轮询游标 */
    private final Map<Long, AtomicLong> cursor = new ConcurrentHashMap<>();

    /** providerId:model → 耗尽标记恢复时间戳 */
    private final Map<String, Long> exhaustedUntil = new ConcurrentHashMap<>();

    /**
     * 取下一个应使用的模型：池为空则回落默认模型；池内全耗尽同样回落默认模型。
     * 每次调用推进一次游标。
     */
    public String nextModel(AiProvider provider) {
        List<String> pool = parsePool(provider);
        if (pool.isEmpty()) {
            return provider.getDefaultModel();
        }
        int size = pool.size();
        AtomicLong cur = cursor.computeIfAbsent(provider.getId(), id -> new AtomicLong());
        long base = cur.get();
        for (int k = 0; k < size; k++) {
            int idx = (int) ((base + k) % size);
            String model = pool.get(idx);
            if (!isExhausted(provider.getId(), model)) {
                // 游标推进到「选中项的下一位」，而不是无条件 +1：
                // 若跳过耗尽项还照样 +1，剩余可用模型的分摊会变歪
                // （实测池 [m1,m2,m3] 且 m1 耗尽时，会出现 m2,m2,m3 而不是 m2,m3,m2）。
                cur.set((long) idx + 1);
                return model;
            }
        }
        // 全耗尽 → 降级到默认模型（便宜档位，按量付费），不能让服务直接不可用
        log.warn("[PivotOS] 模型池全部额度耗尽，降级到默认模型：providerId={} pool={} defaultModel={}",
                provider.getId(), pool, provider.getDefaultModel());
        return provider.getDefaultModel();
    }

    /**
     * 预览「下一次会用到哪个模型」，<b>不推进游标</b>。
     * 给只读场景（如前端展示当前生效模型）用——避免查询动作本身把轮询位置挪走。
     */
    public String peekModel(AiProvider provider) {
        List<String> pool = parsePool(provider);
        if (pool.isEmpty()) {
            return provider.getDefaultModel();
        }
        long base = cursor.getOrDefault(provider.getId(), new AtomicLong()).get();
        for (int k = 0; k < pool.size(); k++) {
            String model = pool.get((int) ((base + k) % pool.size()));
            if (!isExhausted(provider.getId(), model)) {
                return model;
            }
        }
        return provider.getDefaultModel();
    }

    /** 本次请求显式指定模型时优先用它（不轮询，尊重调用方选择） */
    public String resolveModel(AiProvider provider, String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel.strip();
        }
        return nextModel(provider);
    }

    /**
     * 解析模型池：逗号分隔 → 去空 / 去重 / 保序。
     * 空或只有分隔符时返回空列表，此时行为退化为「只用 defaultModel」。
     */
    public List<String> parsePool(AiProvider provider) {
        String raw = provider.getModelPool();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String m = part.strip();
            if (!m.isEmpty()) {
                set.add(m);
            }
        }
        return new ArrayList<>(set);
    }

    /** 标记某模型免费额度耗尽（跳过 EXHAUSTED_TTL_MILLIS 后自动恢复） */
    public void markExhausted(Long providerId, String model) {
        if (providerId == null || model == null || model.isBlank()) {
            return;
        }
        exhaustedUntil.put(providerId + ":" + model, System.currentTimeMillis() + EXHAUSTED_TTL_MILLIS);
        log.warn("[PivotOS] 模型免费额度耗尽已摘除：providerId={} model={}（{} 小时后自动恢复重试）",
                providerId, model, EXHAUSTED_TTL_MILLIS / 3600_000);
    }

    /**
     * 同 {@link #markExhausted(Long, String)}，TTL 可指定。
     *
     * <p>测试接缝：默认 TTL 是 12 小时，单元测试不可能真等 12 小时去验证「自动恢复」，
     * 所以留一个包内可见的重载供 {@code AiModelPoolRouterTest} 用 1ms TTL 验证。
     */
    void markExhausted(Long providerId, String model, long ttlMillis) {
        if (providerId == null || model == null || model.isBlank()) {
            return;
        }
        exhaustedUntil.put(providerId + ":" + model, System.currentTimeMillis() + ttlMillis);
    }

    /** 该模型是否处于「已耗尽」状态（过期自动视为可用） */
    public boolean isExhausted(Long providerId, String model) {
        Long until = exhaustedUntil.get(providerId + ":" + model);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            exhaustedUntil.remove(providerId + ":" + model);
            return false;
        }
        return true;
    }

    /** 清空某供应商的耗尽标记与游标（供应商配置变更时调用） */
    public void evictProvider(Long providerId) {
        String prefix = providerId + ":";
        exhaustedUntil.keySet().removeIf(k -> k.startsWith(prefix));
        cursor.remove(providerId);
    }

    /**
     * 判断异常是否为「免费额度耗尽」。
     *
     * <p>百炼的错误码在响应体里，Spring AI / HTTP 客户端会把它包进异常 message，
     * 且真正抛出时往往套了好层 cause（{@code RuntimeException → ... → 根因}），
     * 只比对顶层 {@code e.getMessage()} 会漏判 —— 所以遍历 cause 链。
     */
    public static boolean isFreeTierExhausted(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains(FREE_TIER_EXHAUSTED_CODE)) {
                return true;
            }
            Throwable next = cur.getCause();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return false;
    }
}

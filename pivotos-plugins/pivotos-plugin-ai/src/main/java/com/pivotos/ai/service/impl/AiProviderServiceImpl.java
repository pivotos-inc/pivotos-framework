package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.client.AiClientRegistry;
import com.pivotos.ai.client.DelegatingEmbeddingModel;
import com.pivotos.ai.domain.dto.ApiKeySaveRequest;
import com.pivotos.ai.domain.dto.ProviderSaveRequest;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.domain.vo.ApiKeyVO;
import com.pivotos.ai.domain.vo.ProviderOptionVO;
import com.pivotos.ai.domain.vo.ProviderVO;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiProviderMapper;
import com.pivotos.ai.service.AiProviderService;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.facade.IMessageFacade;
import com.pivotos.starter.ai.config.AiProperties;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 供应商与 API Key 管理实现。
 * Key 明文只在「录入 → AES 落库」与「调用链取用」两条路径出现，
 * 列表/详情一律脱敏（尾 4 位），不提供明文回看。
 * 配置变更同步失效 AiClientRegistry 缓存，动态 client 即时生效。
 *
 * <p>多租户（S23）：管理侧 CRUD 走 MP 标准方法，由租户拦截器按 TenantContext
 * 自动行级隔离；解析链（选项/默认供应商/取 Key）用 @InterceptorIgnore 显式
 * 两段查询「当前租户 → 空则平台（tenant_id=0）兜底」，tenant 开关开/关行为一致。
 *
 * <p>Key 健康度（S23）：连续失败计数达阈值（pivotos.ai.key-fail-threshold，默认 3）
 * 原子停用（UPDATE 带 status=0 AND fail_count>=N 条件，天然防重复告警），
 * 并通过 message 插件 -api 契约发站内信告警（ObjectProvider 可选注入，无实现不阻断）。
 */
@Service
@RequiredArgsConstructor
public class AiProviderServiceImpl implements AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderServiceImpl.class);

    /** 平台/默认租户 ID（租户无自有配置时的兜底来源） */
    private static final long PLATFORM_TENANT_ID = 0L;

    /** 告警兜底接收人（Key 创建人缺失/系统占位时回落 admin） */
    private static final long FALLBACK_RECEIVER_ID = 1L;

    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper apiKeyMapper;
    private final AiClientRegistry clientRegistry;
    private final AiProperties aiProperties;
    /** message 插件未装配时降级为仅记日志（只依赖 -api 契约） */
    private final ObjectProvider<IMessageFacade> messageFacadeProvider;
    /** 动态 EmbeddingModel 委托（ObjectProvider 打破与 DelegatingEmbeddingModel 的循环依赖） */
    private final ObjectProvider<DelegatingEmbeddingModel> embeddingModelProvider;

    // ---------- 供应商管理 ----------

    @Override
    public List<ProviderVO> listProviders() {
        return providerMapper.selectList(Wrappers.<AiProvider>lambdaQuery()
                        .orderByAsc(AiProvider::getSort)
                        .orderByAsc(AiProvider::getId))
                .stream().map(this::toProviderVO).toList();
    }

    @Override
    public Long createProvider(ProviderSaveRequest request) {
        checkCodeUnique(request.getCode(), null);
        AiProvider entity = new AiProvider();
        fillProvider(entity, request);
        // fill=INSERT 字段恒入 INSERT 列，无租户上下文时填充器不填会插 NULL 撞 NOT NULL 约束，
        // 这里显式落当前租户（平台视角 = 0）
        entity.setTenantId(currentTenantId());
        providerMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateProvider(ProviderSaveRequest request) {
        AiProvider existing = requireProvider(request.getId());
        checkCodeUnique(request.getCode(), existing.getId());
        fillProvider(existing, request);
        providerMapper.updateById(existing);
        clientRegistry.evictProvider(existing.getId());
        evictEmbeddingModel();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProvider(Long id) {
        requireProvider(id);
        providerMapper.deleteById(id);
        apiKeyMapper.delete(Wrappers.<AiApiKey>lambdaQuery().eq(AiApiKey::getProviderId, id));
        clientRegistry.evictProvider(id);
        evictEmbeddingModel();
    }

    // ---------- 对话侧只读 ----------

    @Override
    public List<ProviderOptionVO> listOptions() {
        return listActiveProviders().stream()
                .map(p -> new ProviderOptionVO(p.getId(), p.getName(), p.getDefaultModel()))
                .toList();
    }

    @Override
    public List<String> listModels(Long providerId) {
        AiProvider provider = requireActiveProvider(providerId);
        List<AiApiKey> keys = listActiveKeys(providerId);
        if (keys.isEmpty()) {
            throw new ServiceException(AiErrorCode.NO_AVAILABLE_KEY);
        }
        return clientRegistry.listModels(provider, keys.get(clientRegistry.nextKeyIndex(providerId, keys.size())));
    }

    @Override
    public AiProvider requireActiveProvider(Long providerId) {
        // 跨租户显式查后手工校验归属：只允许用本租户或平台兜底的供应商，
        // 其他租户的一律 5021（不泄露资源存在性）
        AiProvider provider = providerId == null ? null : providerMapper.selectByIdAnyTenant(providerId);
        if (provider == null || provider.getStatus() != 0
                || !belongsToCurrentOrPlatform(provider.getTenantId())) {
            throw new ServiceException(AiErrorCode.PROVIDER_NOT_FOUND);
        }
        return provider;
    }

    @Override
    public AiProvider findDefaultProvider() {
        return listActiveProviders().stream()
                .filter(p -> !listActiveKeys(p.getId()).isEmpty())
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<AiApiKey> listActiveKeys(Long providerId) {
        // Key 随归属供应商同租户，供应商归属已在 requireActiveProvider 校验，
        // 这里跨租户直查（平台兜底 Key 在租户上下文下也能取到）
        return apiKeyMapper.selectActiveByProvider(providerId);
    }

    // ---------- 向量化侧只读（S61） ----------

    @Override
    public AiProvider findEmbeddingProvider() {
        return listActiveProviders().stream()
                .filter(p -> !listActiveEmbeddingKeys(p.getId()).isEmpty())
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<AiApiKey> listActiveEmbeddingKeys(Long providerId) {
        return apiKeyMapper.selectActiveByPurpose(providerId, "embedding");
    }

    @Override
    public void evictEmbeddingModel() {
        DelegatingEmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model != null) {
            model.evict();
        }
    }

    // ---------- Key 健康度 ----------

    @Override
    public void recordKeyFailure(Long keyId) {
        if (keyId == null) {
            return;
        }
        try {
            apiKeyMapper.incrementFailCount(keyId);
            int threshold = aiProperties.getKeyFailThreshold() == null
                    ? 3 : aiProperties.getKeyFailThreshold();
            // 原子停用：status=0 AND fail_count>=N 才命中，并发/重复失败只有一次 affected>0，告警不重复
            if (apiKeyMapper.disableIfFailExceeded(keyId, threshold) > 0) {
                AiApiKey key = apiKeyMapper.selectBriefById(keyId);
                if (key != null) {
                    clientRegistry.evictKey(key.getProviderId(), keyId);
                    log.warn("[PivotOS] AI Key 连续失败 {} 次已自动停用：keyId={} providerId={} label={}",
                            threshold, keyId, key.getProviderId(), key.getLabel());
                    notifyKeyDisabled(key, threshold);
                }
            }
        } catch (Exception e) {
            // 健康度记账失败不影响对话主链路（调用方可能在流式回调线程）
            log.warn("[PivotOS] AI Key 失败计数记账异常：keyId={}", keyId, e);
        }
    }

    @Override
    public void recordKeySuccess(Long keyId) {
        if (keyId == null) {
            return;
        }
        try {
            apiKeyMapper.resetFailCount(keyId);
        } catch (Exception e) {
            log.warn("[PivotOS] AI Key 失败计数清零异常：keyId={}", keyId, e);
        }
    }

    // ---------- Key 管理 ----------

    @Override
    public List<ApiKeyVO> listKeys(Long providerId) {
        return apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                        .eq(AiApiKey::getProviderId, providerId)
                        .orderByAsc(AiApiKey::getId))
                .stream().map(this::toKeyVO).toList();
    }

    @Override
    public Long createKey(ApiKeySaveRequest request) {
        requireProvider(request.getProviderId());
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new ServiceException(AiErrorCode.API_KEY_EMPTY);
        }
        AiApiKey entity = new AiApiKey();
        entity.setProviderId(request.getProviderId());
        entity.setLabel(request.getLabel() == null ? "" : request.getLabel());
        // purpose 默认 all（对话+向量化通用），合法值 chat/embedding/all
        String purpose = request.getPurpose();
        entity.setPurpose(purpose == null || purpose.isBlank() ? "all" : purpose.toLowerCase());
        entity.setApiKey(request.getApiKey().strip());
        entity.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        // Key 随归属供应商同租户（平台视角 = 0），同上避免 fill=INSERT 插 NULL
        entity.setTenantId(currentTenantId());
        apiKeyMapper.insert(entity);
        // 新增 embedding/all 用途 Key 可能影响 EmbeddingModel 解析
        if ("embedding".equals(entity.getPurpose()) || "all".equals(entity.getPurpose())) {
            evictEmbeddingModel();
        }
        return entity.getId();
    }

    @Override
    public void updateKey(ApiKeySaveRequest request) {
        AiApiKey existing = requireKey(request.getId());
        if (request.getLabel() != null) {
            existing.setLabel(request.getLabel());
        }
        if (request.getPurpose() != null && !request.getPurpose().isBlank()) {
            existing.setPurpose(request.getPurpose().toLowerCase());
        }
        if (request.getStatus() != null) {
            // 停用 → 重新启用视为人工修复，连续失败计数清零，避免一次抖动即再次自动停用
            if (request.getStatus() == 0 && existing.getStatus() != null && existing.getStatus() == 1) {
                existing.setFailCount(0);
            }
            existing.setStatus(request.getStatus());
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            existing.setApiKey(request.getApiKey().strip());
        }
        apiKeyMapper.updateById(existing);
        clientRegistry.evictKey(existing.getProviderId(), existing.getId());
        // Key 变更可能影响 EmbeddingModel 解析（purpose/status/apiKey 均可变）
        evictEmbeddingModel();
    }

    @Override
    public void deleteKey(Long id) {
        AiApiKey existing = requireKey(id);
        apiKeyMapper.deleteById(id);
        clientRegistry.evictKey(existing.getProviderId(), id);
        evictEmbeddingModel();
    }

    // ---------- 私有工具 ----------

    /** 解析链取启用供应商：当前租户自有配置优先，无则平台（tenant_id=0）兜底 */
    private List<AiProvider> listActiveProviders() {
        long current = currentTenantId();
        List<AiProvider> providers = providerMapper.selectActiveByTenant(current);
        if (providers.isEmpty() && current != PLATFORM_TENANT_ID) {
            providers = providerMapper.selectActiveByTenant(PLATFORM_TENANT_ID);
        }
        return providers;
    }

    /** 当前租户 ID（无租户上下文 = 平台视角，与 ColumnTenantStrategy 兜底值一致） */
    private long currentTenantId() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    /** 归属校验：本租户或平台兜底可用，其他租户的不可见 */
    private boolean belongsToCurrentOrPlatform(Long tenantId) {
        long owner = tenantId == null ? PLATFORM_TENANT_ID : tenantId;
        return owner == currentTenantId() || owner == PLATFORM_TENANT_ID;
    }

    /** Key 自动停用站内信告警：发给 Key 创建人（缺失/系统占位回落 admin），失败只记日志不阻断 */
    private void notifyKeyDisabled(AiApiKey key, int threshold) {
        IMessageFacade facade = messageFacadeProvider.getIfAvailable();
        if (facade == null) {
            log.warn("[PivotOS] message 插件未装配，Key 自动停用告警降级为日志：keyId={}", key.getId());
            return;
        }
        try {
            AiProvider provider = providerMapper.selectByIdAnyTenant(key.getProviderId());
            String providerName = provider == null ? String.valueOf(key.getProviderId()) : provider.getName();
            String label = key.getLabel() == null || key.getLabel().isBlank() ? "（未命名）" : key.getLabel();
            Long receiver = key.getCreateBy() == null || key.getCreateBy() <= 0
                    ? FALLBACK_RECEIVER_ID : key.getCreateBy();
            MessageSendCmd cmd = new MessageSendCmd();
            cmd.setTitle("AI Key 自动停用告警");
            cmd.setContent("供应商【" + providerName + "】的 Key【" + label + "】连续失败 "
                    + threshold + " 次，已自动停用。请检查 Key 有效性与额度，修复后在 AI 配置页重新启用。");
            cmd.setMsgType(1);
            cmd.setChannel("inbox");
            cmd.setBizType("ai-key-health");
            cmd.setBizId(String.valueOf(key.getId()));
            cmd.setReceiverIds(List.of(receiver));
            facade.send(cmd);
        } catch (Exception e) {
            log.warn("[PivotOS] Key 自动停用告警发送失败：keyId={}", key.getId(), e);
        }
    }

    private AiProvider requireProvider(Long id) {
        AiProvider provider = id == null ? null : providerMapper.selectById(id);
        if (provider == null) {
            throw new ServiceException(AiErrorCode.PROVIDER_NOT_FOUND);
        }
        return provider;
    }

    private AiApiKey requireKey(Long id) {
        AiApiKey key = id == null ? null : apiKeyMapper.selectById(id);
        if (key == null) {
            throw new ServiceException(AiErrorCode.API_KEY_NOT_FOUND);
        }
        return key;
    }

    /** code 查重（逻辑删除范围外自动排除；excludeId 用于修改自身） */
    private void checkCodeUnique(String code, Long excludeId) {
        Long count = providerMapper.selectCount(Wrappers.<AiProvider>lambdaQuery()
                .eq(AiProvider::getCode, code)
                .ne(excludeId != null, AiProvider::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException(AiErrorCode.PROVIDER_CODE_DUPLICATE);
        }
    }

    private void fillProvider(AiProvider entity, ProviderSaveRequest request) {
        entity.setName(request.getName());
        entity.setCode(request.getCode());
        entity.setBaseUrl(request.getBaseUrl().strip());
        entity.setDefaultModel(request.getDefaultModel() == null ? "" : request.getDefaultModel().strip());
        entity.setEmbeddingModel(request.getEmbeddingModel() == null ? "" : request.getEmbeddingModel().strip());
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        entity.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        entity.setRemark(request.getRemark());
    }

    private ProviderVO toProviderVO(AiProvider entity) {
        ProviderVO vo = new ProviderVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setCode(entity.getCode());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setDefaultModel(entity.getDefaultModel());
        vo.setEmbeddingModel(entity.getEmbeddingModel());
        vo.setSort(entity.getSort());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setTenantId(entity.getTenantId());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setActiveKeyCount(apiKeyMapper.selectCount(Wrappers.<AiApiKey>lambdaQuery()
                .eq(AiApiKey::getProviderId, entity.getId())
                .eq(AiApiKey::getStatus, 0)));
        return vo;
    }

    /** Key 脱敏：只回尾 4 位（短于 8 位全掩） */
    private ApiKeyVO toKeyVO(AiApiKey entity) {
        ApiKeyVO vo = new ApiKeyVO();
        vo.setId(entity.getId());
        vo.setProviderId(entity.getProviderId());
        vo.setLabel(entity.getLabel());
        vo.setPurpose(entity.getPurpose());
        vo.setStatus(entity.getStatus());
        vo.setFailCount(entity.getFailCount());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        String plain = entity.getApiKey();
        vo.setKeyMasked(plain == null || plain.length() < 8
                ? "****"
                : "****" + plain.substring(plain.length() - 4));
        return vo;
    }
}

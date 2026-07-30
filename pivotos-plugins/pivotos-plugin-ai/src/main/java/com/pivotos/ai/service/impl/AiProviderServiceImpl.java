package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.client.AiClientRegistry;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 供应商与 API Key 管理实现。
 * Key 明文只在「录入 → AES 落库」与「调用链取用」两条路径出现，
 * 列表/详情一律脱敏（尾 4 位），不提供明文回看。
 * 配置变更同步失效 AiClientRegistry 缓存，动态 client 即时生效。
 */
@Service
@RequiredArgsConstructor
public class AiProviderServiceImpl implements AiProviderService {

    private final AiProviderMapper providerMapper;
    private final AiApiKeyMapper apiKeyMapper;
    private final AiClientRegistry clientRegistry;

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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProvider(Long id) {
        requireProvider(id);
        providerMapper.deleteById(id);
        apiKeyMapper.delete(Wrappers.<AiApiKey>lambdaQuery().eq(AiApiKey::getProviderId, id));
        clientRegistry.evictProvider(id);
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
        AiProvider provider = providerMapper.selectById(providerId);
        if (provider == null || provider.getStatus() != 0) {
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
        return apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                .eq(AiApiKey::getProviderId, providerId)
                .eq(AiApiKey::getStatus, 0)
                .orderByAsc(AiApiKey::getId));
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
        entity.setApiKey(request.getApiKey().strip());
        entity.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        apiKeyMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateKey(ApiKeySaveRequest request) {
        AiApiKey existing = requireKey(request.getId());
        if (request.getLabel() != null) {
            existing.setLabel(request.getLabel());
        }
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            existing.setApiKey(request.getApiKey().strip());
        }
        apiKeyMapper.updateById(existing);
        clientRegistry.evictKey(existing.getProviderId(), existing.getId());
    }

    @Override
    public void deleteKey(Long id) {
        AiApiKey existing = requireKey(id);
        apiKeyMapper.deleteById(id);
        clientRegistry.evictKey(existing.getProviderId(), id);
    }

    // ---------- 私有工具 ----------

    private List<AiProvider> listActiveProviders() {
        return providerMapper.selectList(Wrappers.<AiProvider>lambdaQuery()
                .eq(AiProvider::getStatus, 0)
                .orderByAsc(AiProvider::getSort)
                .orderByAsc(AiProvider::getId));
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
        vo.setSort(entity.getSort());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
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
        vo.setStatus(entity.getStatus());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        String plain = entity.getApiKey();
        vo.setKeyMasked(plain == null || plain.length() < 8
                ? "****"
                : "****" + plain.substring(plain.length() - 4));
        return vo;
    }
}

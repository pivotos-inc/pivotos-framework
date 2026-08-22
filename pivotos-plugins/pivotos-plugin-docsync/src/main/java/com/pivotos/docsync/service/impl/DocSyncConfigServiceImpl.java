package com.pivotos.docsync.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.docsync.adapter.DocSyncAdapter;
import com.pivotos.docsync.api.dto.DocSyncConfigDTO;
import com.pivotos.docsync.api.dto.DocSyncConfigFieldDTO;
import com.pivotos.docsync.api.dto.DocSyncConfigQuery;
import com.pivotos.docsync.api.dto.DocSyncConfigSaveRequest;
import com.pivotos.docsync.api.enums.DocSyncErrorCode;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.entity.DocSyncConfig;
import com.pivotos.docsync.mapper.DocSyncConfigMapper;
import com.pivotos.docsync.service.DocSyncConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档同步配置 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocSyncConfigServiceImpl extends ServiceImpl<DocSyncConfigMapper, DocSyncConfig>
        implements DocSyncConfigService {

    private final List<DocSyncAdapter> adapters;

    /**
     * 按平台类型索引的适配器缓存
     */
    private Map<DocSyncTypeEnum, DocSyncAdapter> adapterMap;

    private Map<DocSyncTypeEnum, DocSyncAdapter> getAdapterMap() {
        if (adapterMap == null) {
            adapterMap = new HashMap<>();
            for (DocSyncAdapter adapter : adapters) {
                adapterMap.put(adapter.getType(), adapter);
            }
        }
        return adapterMap;
    }

    @Override
    public PageResult<DocSyncConfigDTO> pageConfigs(DocSyncConfigQuery query) {
        LambdaQueryWrapper<DocSyncConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(query.getName() != null && !query.getName().isBlank(),
                        DocSyncConfig::getName, query.getName())
                .eq(query.getPlatformType() != null,
                        DocSyncConfig::getPlatformType, query.getPlatformType() != null ? query.getPlatformType().name() : null)
                .eq(query.getEnabled() != null,
                        DocSyncConfig::getEnabled, query.getEnabled())
                .orderByDesc(DocSyncConfig::getCreateTime);

        Page<DocSyncConfig> page = new Page<>(query.getPageNum(), query.getPageSize());
        page = page(page, wrapper);

        List<DocSyncConfigDTO> dtoList = page.getRecords().stream()
                .map(this::toDTO)
                .toList();

        return new PageResult<>(dtoList, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public DocSyncConfigDTO getConfig(Long id) {
        DocSyncConfig config = getById(id);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        return toDTO(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveConfig(DocSyncConfigSaveRequest request) {
        // 检查名称唯一性
        long count = count(new LambdaQueryWrapper<DocSyncConfig>()
                .eq(DocSyncConfig::getName, request.getName())
                .ne(request.getId() != null, DocSyncConfig::getId, request.getId()));
        if (count > 0) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NAME_EXISTS);
        }

        // 按平台校验必填字段
        validateRequiredFields(request);

        DocSyncConfig config;
        if (request.getId() != null) {
            config = getById(request.getId());
            if (config == null) {
                throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
            }
        } else {
            config = new DocSyncConfig();
        }

        BeanUtil.copyProperties(request, config);
        // 枚举转字符串存储
        if (request.getPlatformType() != null) {
            config.setPlatformType(request.getPlatformType().name());
        }
        if (config.getEnabled() == null) {
            config.setEnabled(1);
        }

        saveOrUpdate(config);
        return config.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        DocSyncConfig config = getById(id);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer enabled) {
        DocSyncConfig config = getById(id);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        config.setEnabled(enabled);
        updateById(config);
    }

    private DocSyncConfigDTO toDTO(DocSyncConfig entity) {
        DocSyncConfigDTO dto = new DocSyncConfigDTO();
        BeanUtil.copyProperties(entity, dto);
        // 字符串转枚举
        if (entity.getPlatformType() != null) {
            try {
                dto.setPlatformType(DocSyncTypeEnum.valueOf(entity.getPlatformType()));
            } catch (IllegalArgumentException ignored) {
                // 兼容数据库中未知的平台类型
            }
        }
        return dto;
    }

    /**
     * 按平台校验必填配置字段
     *
     * <p>根据适配器声明的配置字段元数据，检查请求中所有 required 字段是否已填写。
     */
    private void validateRequiredFields(DocSyncConfigSaveRequest request) {
        DocSyncAdapter adapter = getAdapterMap().get(request.getPlatformType());
        if (adapter == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_TYPE_NOT_SUPPORTED);
        }
        for (DocSyncConfigFieldDTO field : adapter.getConfigFields()) {
            if (field.isRequired()) {
                String value = getFieldValue(request, field.getFieldKey());
                if (value == null || value.isBlank()) {
                    throw new ServiceException(DocSyncErrorCode.CONFIG_FIELD_REQUIRED);
                }
            }
        }
    }

    /**
     * 从保存请求中按字段 key 提取属性值
     */
    private String getFieldValue(DocSyncConfigSaveRequest request, String fieldKey) {
        return switch (fieldKey) {
            case "serverUrl" -> request.getServerUrl();
            case "credential" -> request.getCredential();
            case "secondaryCredential" -> request.getSecondaryCredential();
            case "projectId" -> request.getProjectId();
            default -> null;
        };
    }
}

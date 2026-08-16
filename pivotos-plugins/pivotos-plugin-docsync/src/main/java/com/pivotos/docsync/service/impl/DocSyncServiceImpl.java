package com.pivotos.docsync.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.docsync.adapter.DocSyncAdapter;
import com.pivotos.docsync.api.dto.PlatformInfoDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncErrorCode;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.api.enums.SyncStatusEnum;
import com.pivotos.docsync.entity.DocSyncConfig;
import com.pivotos.docsync.entity.DocSyncLog;
import com.pivotos.docsync.mapper.DocSyncLogMapper;
import com.pivotos.docsync.service.DocSyncConfigService;
import com.pivotos.docsync.service.DocSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档同步 Service 实现（统一同步入口）
 *
 * <p>通过适配器模式将不同平台的同步逻辑解耦，DocSyncService 仅负责：
 * <ol>
 *   <li>获取 OpenAPI JSON（从 SpringDoc /v3/api-docs 端点）</li>
 *   <li>路由到对应的 {@link DocSyncAdapter}</li>
 *   <li>记录同步日志</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocSyncServiceImpl implements DocSyncService {

    private final List<DocSyncAdapter> adapters;
    private final DocSyncConfigService configService;
    private final DocSyncLogMapper logMapper;
    private final Environment environment;

    /**
     * 按平台类型索引的适配器缓存
     */
    private Map<DocSyncTypeEnum, DocSyncAdapter> adapterMap;

    /**
     * 懒加载适配器索引
     */
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
    public List<PlatformInfoDTO> listSupportedPlatforms() {
        return Arrays.stream(DocSyncTypeEnum.values())
                .map(this::toPlatformInfo)
                .collect(Collectors.toList());
    }

    @Override
    public PlatformInfoDTO getPlatformInfo(DocSyncTypeEnum type) {
        return toPlatformInfo(type);
    }

    /**
     * 构建平台信息 DTO（含配置字段元数据）
     */
    private PlatformInfoDTO toPlatformInfo(DocSyncTypeEnum type) {
        PlatformInfoDTO info = new PlatformInfoDTO();
        info.setType(type);
        info.setDisplayName(type.getDisplayName());
        info.setAutoSyncSupported(type.isAutoSyncSupported());
        info.setDescription(type.getDescription());
        // 从对应适配器获取配置字段元数据
        DocSyncAdapter adapter = getAdapterMap().get(type);
        if (adapter != null) {
            info.setConfigFields(adapter.getConfigFields());
        }
        return info;
    }

    @Override
    public boolean testConnection(Long configId) {
        DocSyncConfig config = configService.getById(configId);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        DocSyncAdapter adapter = getAdapter(config);
        if (adapter == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_TYPE_NOT_SUPPORTED);
        }
        return adapter.testConnection(config);
    }

    @Override
    public SyncResultDTO syncOne(Long configId) {
        DocSyncConfig config = configService.getById(configId);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        if (config.getEnabled() != null && config.getEnabled() == 0) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }

        DocSyncAdapter adapter = getAdapter(config);
        if (adapter == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_TYPE_NOT_SUPPORTED);
        }

        // 获取 OpenAPI JSON
        String openApiJson = fetchOpenApiJson();

        // 执行同步
        SyncResultDTO result = adapter.sync(openApiJson, config);

        // 记录同步日志
        saveSyncLog(config, result);

        // 更新配置的最近同步状态
        updateConfigSyncStatus(config, result);

        return result;
    }

    @Override
    public List<SyncResultDTO> syncAll() {
        List<DocSyncConfig> configs = configService.list(new LambdaQueryWrapper<DocSyncConfig>()
                .eq(DocSyncConfig::getEnabled, 1));

        return configs.stream()
                .map(config -> {
                    try {
                        return syncOne(config.getId());
                    } catch (Exception e) {
                        log.error("[DocSync] 同步配置 {} 失败", config.getName(), e);
                        return SyncResultDTO.builder()
                                .status(SyncStatusEnum.FAILED)
                                .apiCount(0)
                                .message("同步异常: " + e.getMessage())
                                .syncTime(LocalDateTime.now())
                                .build();
                    }
                })
                .toList();
    }

    @Override
    public String exportOpenApiJson(Long configId) {
        DocSyncConfig config = configService.getById(configId);
        if (config == null) {
            throw new ServiceException(DocSyncErrorCode.CONFIG_NOT_FOUND);
        }
        return fetchOpenApiJson();
    }

    /**
     * 从 SpringDoc 端点获取 OpenAPI JSON
     */
    private String fetchOpenApiJson() {
        try {
            String port = environment.getProperty("server.port", "8080");
            String contextPath = environment.getProperty("server.servlet.context-path", "");
            String url = "http://localhost:" + port + contextPath + "/v3/api-docs";
            String json = HttpUtil.get(url, 10000);
            // 验证 JSON 有效性
            JSONUtil.parseObj(json);
            return json;
        } catch (Exception e) {
            log.error("[DocSync] 获取 OpenAPI JSON 失败", e);
            throw new ServiceException(DocSyncErrorCode.SYNC_OPENAPI_FETCH_FAILED);
        }
    }

    /**
     * 根据配置获取对应的适配器
     */
    private DocSyncAdapter getAdapter(DocSyncConfig config) {
        try {
            DocSyncTypeEnum type = DocSyncTypeEnum.valueOf(config.getPlatformType());
            return getAdapterMap().get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 记录同步日志
     */
    private void saveSyncLog(DocSyncConfig config, SyncResultDTO result) {
        DocSyncLog syncLog = new DocSyncLog();
        syncLog.setConfigId(config.getId());
        syncLog.setPlatformType(config.getPlatformType());
        syncLog.setStatus(result.getStatus().name());
        syncLog.setApiCount(result.getApiCount());
        syncLog.setElapsedMs(result.getElapsedMs());
        syncLog.setResult(result.getMessage());
        syncLog.setSyncTime(result.getSyncTime() != null ? result.getSyncTime() : LocalDateTime.now());
        logMapper.insert(syncLog);
    }

    /**
     * 更新配置的最近同步状态
     */
    private void updateConfigSyncStatus(DocSyncConfig config, SyncResultDTO result) {
        config.setLastSyncTime(result.getSyncTime() != null ? result.getSyncTime() : LocalDateTime.now());
        config.setLastSyncStatus(result.getStatus().name());
        config.setLastSyncResult(result.getMessage());
        configService.updateById(config);
    }
}

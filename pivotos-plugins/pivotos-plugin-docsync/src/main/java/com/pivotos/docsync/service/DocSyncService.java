package com.pivotos.docsync.service;

import com.pivotos.docsync.api.dto.PlatformInfoDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;

import java.util.List;

/**
 * 文档同步 Service（统一同步入口）
 */
public interface DocSyncService {

    /**
     * 获取所有支持的平台信息（含配置字段元数据）
     */
    List<PlatformInfoDTO> listSupportedPlatforms();

    /**
     * 获取指定平台的信息（含配置字段元数据）
     *
     * @param type 平台类型
     * @return 平台信息
     */
    PlatformInfoDTO getPlatformInfo(DocSyncTypeEnum type);

    /**
     * 测试平台连通性
     *
     * @param configId 配置 ID
     * @return true 连通成功
     */
    boolean testConnection(Long configId);

    /**
     * 同步单个配置
     *
     * @param configId 配置 ID
     * @return 同步结果
     */
    SyncResultDTO syncOne(Long configId);

    /**
     * 同步所有启用的配置
     *
     * @return 各配置的同步结果列表
     */
    List<SyncResultDTO> syncAll();

    /**
     * 导出 OpenAPI JSON（供手动同步平台使用）
     *
     * @param configId 配置 ID
     * @return OpenAPI JSON 字符串
     */
    String exportOpenApiJson(Long configId);
}

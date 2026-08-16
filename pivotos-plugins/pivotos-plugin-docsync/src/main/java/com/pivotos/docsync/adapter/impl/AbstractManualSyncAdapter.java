package com.pivotos.docsync.adapter.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pivotos.docsync.adapter.DocSyncAdapter;
import com.pivotos.docsync.api.dto.DocSyncConfigFieldDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.SyncStatusEnum;
import com.pivotos.docsync.entity.DocSyncConfig;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 手动同步适配器基类
 *
 * <p>不支持自动同步的平台（XXL-API、ApiPost、Eolink）继承此类。
 * 这些平台无开放 API，仅提供 OpenAPI JSON 导出和管理后台跳转。
 */
public abstract class AbstractManualSyncAdapter implements DocSyncAdapter {

    @Override
    public boolean supportsAutoSync() {
        return false;
    }

    /**
     * 手动平台不支持连通性测试，直接返回 true
     */
    @Override
    public boolean testConnection(DocSyncConfig config) {
        return true;
    }

    /**
     * 手动平台不支持自动同步，返回手动导出结果
     */
    @Override
    public SyncResultDTO sync(String openApiJson, DocSyncConfig config) {
        int apiCount = countApis(openApiJson);
        return SyncResultDTO.builder()
                .status(SyncStatusEnum.MANUAL_EXPORT)
                .apiCount(apiCount)
                .message("该平台不支持自动同步，请下载 OpenAPI JSON 文件后手动导入")
                .syncTime(LocalDateTime.now())
                .managementUrl(getManagementUrl(config))
                .build();
    }

    @Override
    public List<DocSyncConfigFieldDTO> getConfigFields() {
        return List.of(
                new DocSyncConfigFieldDTO("serverUrl", "管理后台地址", true, "url",
                        "http://xxl-api.local", "平台管理后台地址，用于手动跳转导入"));
    }

    /**
     * 统计 OpenAPI JSON 中的接口数量（paths 下每个 HTTP method 计为一个接口）
     */
    private int countApis(String openApiJson) {
        if (openApiJson == null || openApiJson.isBlank()) {
            return 0;
        }
        try {
            JSONObject json = JSONUtil.parseObj(openApiJson);
            JSONObject paths = json.getJSONObject("paths");
            if (paths == null) {
                return 0;
            }
            int count = 0;
            for (String pathKey : paths.keySet()) {
                JSONObject pathItem = paths.getJSONObject(pathKey);
                if (pathItem == null) {
                    continue;
                }
                count += pathItem.size();
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}

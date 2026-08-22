package com.pivotos.docsync.adapter;

import com.pivotos.docsync.api.dto.DocSyncConfigFieldDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.entity.DocSyncConfig;

import java.util.List;

/**
 * 文档同步适配器接口
 *
 * <p>每个外部文档平台对应一个适配器实现，通过 {@link DocSyncTypeEnum} 标识类型。
 * 支持自动同步的平台实现 {@link #sync}，不支持的平台仅提供手动导出和跳转。
 */
public interface DocSyncAdapter {

    /**
     * 平台类型标识
     */
    DocSyncTypeEnum getType();

    /**
     * 是否支持自动同步
     */
    default boolean supportsAutoSync() {
        return getType().isAutoSyncSupported();
    }

    /**
     * 测试平台连通性
     *
     * @param config 同步配置
     * @return true 连通成功
     */
    boolean testConnection(DocSyncConfig config);

    /**
     * 执行文档同步（仅自动同步平台实现此方法）
     *
     * @param openApiJson OpenAPI 3.0 JSON 字符串
     * @param config      同步配置
     * @return 同步结果
     */
    SyncResultDTO sync(String openApiJson, DocSyncConfig config);

    /**
     * 获取管理后台跳转 URL（手动同步平台使用）
     *
     * @param config 同步配置
     * @return 管理 URL
     */
    default String getManagementUrl(DocSyncConfig config) {
        return config.getServerUrl();
    }

    /**
     * 获取该平台所需的配置字段列表
     *
     * <p>供前端动态渲染配置表单，声明每个字段的 key、标签、是否必填、
     * 输入类型、占位提示和说明文字。
     *
     * @return 配置字段列表
     */
    List<DocSyncConfigFieldDTO> getConfigFields();
}

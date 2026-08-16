package com.pivotos.docsync.adapter.impl;

import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.entity.DocSyncConfig;
import org.springframework.stereotype.Component;

/**
 * ApiPost 平台适配器（手动同步）
 *
 * <p>ApiPost 开放 API 待验证，暂仅提供 OpenAPI JSON 导出和管理后台跳转。
 * 后续如官方开放 API 明确，可升级为自动同步适配器。
 */
@Component
public class ApiPostManualAdapter extends AbstractManualSyncAdapter {

    @Override
    public DocSyncTypeEnum getType() {
        return DocSyncTypeEnum.APIPOST;
    }

    @Override
    public String getManagementUrl(DocSyncConfig config) {
        return config.getServerUrl();
    }
}

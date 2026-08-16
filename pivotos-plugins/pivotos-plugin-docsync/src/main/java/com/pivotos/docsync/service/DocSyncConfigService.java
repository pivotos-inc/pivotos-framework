package com.pivotos.docsync.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.docsync.api.dto.DocSyncConfigDTO;
import com.pivotos.docsync.api.dto.DocSyncConfigQuery;
import com.pivotos.docsync.api.dto.DocSyncConfigSaveRequest;
import com.pivotos.docsync.entity.DocSyncConfig;

/**
 * 文档同步配置 Service
 */
public interface DocSyncConfigService extends IService<DocSyncConfig> {

    /**
     * 分页查询同步配置
     */
    PageResult<DocSyncConfigDTO> pageConfigs(DocSyncConfigQuery query);

    /**
     * 获取配置详情
     */
    DocSyncConfigDTO getConfig(Long id);

    /**
     * 保存（新增或修改）配置
     */
    Long saveConfig(DocSyncConfigSaveRequest request);

    /**
     * 删除配置
     */
    void deleteConfig(Long id);

    /**
     * 切换启用状态
     */
    void changeStatus(Long id, Integer enabled);
}

package com.pivotos.docsync.api.dto;

import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档同步配置 DTO
 */
@Data
public class DocSyncConfigDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 配置名称（如"测试环境-Torna"） */
    private String name;

    /** 平台类型 */
    private DocSyncTypeEnum platformType;

    /** 服务器地址（如 http://torna.local:7700） */
    private String serverUrl;

    /** 认证凭证（Token / API Key，根据平台不同含义不同） */
    private String credential;

    /** 辅助凭证（部分平台需要双凭证，如 ShowDoc 的 api_token） */
    private String secondaryCredential;

    /** 项目 ID（Apifox/YApi 等需要） */
    private String projectId;

    /** 是否启用 */
    private Integer enabled;

    /** 上次同步时间 */
    private LocalDateTime lastSyncTime;

    /** 上次同步状态（SyncStatusEnum） */
    private String lastSyncStatus;

    /** 上次同步结果描述 */
    private String lastSyncResult;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 备注 */
    private String remark;
}

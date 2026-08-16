package com.pivotos.docsync.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文档同步配置
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("doc_sync_config")
public class DocSyncConfig extends BaseDO {

    private static final long serialVersionUID = 1L;

    /** 配置名称 */
    private String name;

    /** 平台类型：TORNA/YAPI/APIFOX/SHOWDOC/XXL_API/APIPOST/EOLINK */
    private String platformType;

    /** 服务器地址 */
    private String serverUrl;

    /** 认证凭证 */
    private String credential;

    /** 辅助凭证 */
    private String secondaryCredential;

    /** 项目 ID */
    private String projectId;

    /** 是否启用 */
    private Integer enabled;

    /** 上次同步时间 */
    private LocalDateTime lastSyncTime;

    /** 上次同步状态 */
    private String lastSyncStatus;

    /** 上次同步结果描述 */
    private String lastSyncResult;

    /** 备注 */
    private String remark;
}

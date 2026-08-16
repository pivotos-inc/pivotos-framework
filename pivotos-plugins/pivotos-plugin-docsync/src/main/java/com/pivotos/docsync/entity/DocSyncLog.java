package com.pivotos.docsync.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文档同步日志
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("doc_sync_log")
public class DocSyncLog extends BaseDO {

    private static final long serialVersionUID = 1L;

    /** 同步配置 ID */
    private Long configId;

    /** 平台类型 */
    private String platformType;

    /** 同步状态：SUCCESS/FAILED/TIMEOUT/MANUAL_EXPORT */
    private String status;

    /** 同步的接口数量 */
    private Integer apiCount;

    /** 耗时（毫秒） */
    private Long elapsedMs;

    /** 结果描述 */
    private String result;

    /** 同步时间 */
    private LocalDateTime syncTime;
}

package com.pivotos.docsync.api.dto;

import com.pivotos.docsync.api.enums.SyncStatusEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档同步日志 DTO
 */
@Data
public class SyncLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 同步配置 ID */
    private Long configId;

    /** 平台类型 */
    private String platformType;

    /** 同步状态 */
    private SyncStatusEnum status;

    /** 同步的接口数量 */
    private Integer apiCount;

    /** 耗时（毫秒） */
    private Long elapsedMs;

    /** 结果描述 */
    private String result;

    /** 同步时间 */
    private LocalDateTime syncTime;
}

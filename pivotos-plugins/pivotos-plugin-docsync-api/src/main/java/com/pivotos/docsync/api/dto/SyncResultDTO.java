package com.pivotos.docsync.api.dto;

import com.pivotos.docsync.api.enums.SyncStatusEnum;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档同步结果
 */
@Data
@Builder
public class SyncResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 同步状态 */
    private SyncStatusEnum status;

    /** 同步的接口数量 */
    private int apiCount;

    /** 结果描述 */
    private String message;

    /** 同步耗时（毫秒） */
    private long elapsedMs;

    /** 同步时间 */
    private LocalDateTime syncTime;

    /** 手动同步时提供的跳转 URL */
    private String managementUrl;
}

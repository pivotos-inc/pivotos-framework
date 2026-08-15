package com.pivotos.file.api.dto;

import lombok.Data;

/**
 * 文件存储统计 DTO（跨 Plugin 契约，运营工作台/数据大屏用，S71）。
 */
@Data
public class FileStatsDTO {

    /** 文件总数 */
    private Long fileCount;

    /** 存储总字节数 */
    private Long totalBytes;
}

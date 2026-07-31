package com.pivotos.file.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 文件元数据分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FilePageQuery extends PageQuery {

    /** 原始文件名（模糊） */
    private String originalName;

    /** 存储类型（minio/oss/cos/obs/s3） */
    private String storageType;
}

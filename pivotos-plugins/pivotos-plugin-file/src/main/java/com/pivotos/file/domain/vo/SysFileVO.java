package com.pivotos.file.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 文件元数据视图对象 */
@Data
public class SysFileVO {

    /** 文件ID */
    private Long id;

    /** 对象键 */
    private String objectKey;

    /** 原始文件名 */
    private String originalName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 内容 MD5 */
    private String md5;

    /** MIME 类型 */
    private String contentType;

    /** 存储类型（minio/oss/cos/obs/s3） */
    private String storageType;

    /** 存储桶 */
    private String bucket;

    /** 上传人 */
    private Long createBy;

    /** 上传时间 */
    private LocalDateTime createTime;
}

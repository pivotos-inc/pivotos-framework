package com.pivotos.file.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件元数据实体（sys_file，S25 2.1-F7）。
 * 多租户：继承 TenantBaseDO 参与行级隔离（sys_file 不在租户内置忽略表清单）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_file")
public class SysFile extends TenantBaseDO {

    /** 对象键（存储内路径，upload/yyyyMMdd/uuid.ext） */
    private String objectKey;

    /** 原始文件名 */
    private String originalName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 内容 MD5（预留，前端暂不计算） */
    private String md5;

    /** MIME 类型 */
    private String contentType;

    /** 存储类型（minio/oss/cos/obs/s3） */
    private String storageType;

    /** 存储桶 */
    private String bucket;
}

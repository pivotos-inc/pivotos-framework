package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移源文件索引表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_file")
public class MigrationFile extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 文件类型：BACKEND/FRONTEND */
    private String fileType;

    /** 相对解压根目录的路径 */
    private String relativePath;

    /** 文件名 */
    private String fileName;

    /** 扩展名 */
    private String extension;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 内容哈希（SHA-256） */
    private String contentHash;

    /** 是否已解析：0 否 1 是 */
    private Boolean parsed;
}

package com.pivotos.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * 文件存储配置（{@code pivotos.file.*}）。
 * 不写 endpoint 即未装配存储，端点返回明确错误而非启动失败。
 * <p>S25 多云扩展字段全部带缺省值，存量 MinIO 配置零改动即兼容。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.file")
public class FileProperties {

    /** 存储类型：minio（缺省，原生 SDK）/ s3（AWS SDK v2，覆盖 OSS/COS/OBS 兼容 API） */
    private String storageType = "minio";

    /** s3 模式下的供应商标识（oss/cos/obs，落 sys_file.storage_type；缺省记 s3） */
    private String vendor;

    /** s3 模式区域（公有云必填，如 oss 的 cn-hangzhou；MinIO S3 模式可缺省） */
    private String region;

    /** path-style 寻址（缺省按类型推导：minio=true，公有云 virtual-host=false） */
    private Boolean pathStyle;

    /** 对象存储 API 地址（如 http://175.24.176.176:9000） */
    private String endpoint;

    /** Access Key */
    private String accessKey;

    /** Secret Key */
    private String secretKey;

    /** 业务桶名 */
    private String bucket = "pivotos";

    /** 前端访问基址（缺省与 endpoint 相同） */
    private String publicUrl;

    /** 预签名有效期（秒） */
    private Integer presignExpireSeconds = 600;

    /** 允许直传的扩展名（小写，不含点） */
    private List<String> allowedExtensions = List.of(
            // 图片
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
            // 文档（知识库 / 通用附件）
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "markdown", "csv", "json",
            // 压缩包
            "zip", "rar", "7z", "tar", "gz"
    );

    /**
     * 生效的访问基址：publicUrl 缺省回落 endpoint，去掉尾斜杠
     */
    public String effectivePublicUrl() {
        String base = publicUrl != null && !publicUrl.isBlank() ? publicUrl : endpoint;
        return base == null ? null : base.replaceAll("/+$", "");
    }

    /**
     * 是否已完成最小配置（三要素）
     */
    public boolean configured() {
        return endpoint != null && !endpoint.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    /**
     * 生效的 path-style 开关：显式配置优先，缺省 minio=true / 其余=false
     */
    public boolean effectivePathStyle() {
        if (pathStyle != null) {
            return pathStyle;
        }
        return "minio".equalsIgnoreCase(storageType);
    }

    /**
     * 生效的区域：未配置时回落 us-east-1（MinIO S3 模式占位，公有云应显式配置）
     */
    public String effectiveRegion() {
        return region != null && !region.isBlank() ? region : "us-east-1";
    }

    /**
     * 落库的存储类型标识：minio，或 s3 模式下的 vendor（缺省 s3）
     */
    public String effectiveStorageLabel() {
        if ("s3".equalsIgnoreCase(storageType)) {
            return vendor != null && !vendor.isBlank() ? vendor.toLowerCase(Locale.ROOT) : "s3";
        }
        return "minio";
    }
}

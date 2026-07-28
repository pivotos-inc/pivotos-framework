package com.pivotos.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 文件存储配置（{@code pivotos.file.*}）。
 * 不写 endpoint 即未装配存储，端点返回明确错误而非启动失败。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.file")
public class FileProperties {

    /** 对象存储 API 地址（如 http://192.168.50.10:9000） */
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
    private List<String> allowedExtensions = List.of("jpg", "jpeg", "png", "gif", "webp");

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
}

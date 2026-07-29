package com.pivotos.file.service.impl;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.file.api.dto.PresignResult;
import com.pivotos.file.api.enums.FileErrorCode;
import com.pivotos.file.config.FileProperties;
import com.pivotos.file.service.FileService;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 预签名直传实现（MinIO）。
 * MinioClient 用 ObjectProvider 延迟解析：未配置存储时报 4020，
 * 不影响插件其余部分装配（条件装配纪律）。
 */
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectProvider<MinioClient> minioClientProvider;
    private final FileProperties properties;

    /** 桶存在性惰性确认标记（避免每次 presign 都发 HeadBucket） */
    private volatile boolean bucketChecked = false;

    @Override
    public PresignResult presignUpload(String filename) {
        MinioClient client = requireClient();
        String ext = extractAndCheckExtension(filename);
        String objectKey = "upload/" + LocalDate.now().format(DATE_DIR)
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        ensureBucket(client);
        try {
            String uploadUrl = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry(properties.getPresignExpireSeconds())
                    .build());
            return new PresignResult(objectKey, uploadUrl,
                    buildFileUrl(objectKey), properties.getPresignExpireSeconds());
        } catch (Exception e) {
            log.error("[PivotOS] 预签名生成失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
        }
    }

    @Override
    public String buildFileUrl(String objectKey) {
        if (!properties.configured()) {
            throw new ServiceException(FileErrorCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        return properties.effectivePublicUrl() + "/" + properties.getBucket() + "/" + objectKey;
    }

    @Override
    public String presignDownload(String objectKeyOrUrl) {
        if (!StringUtils.hasText(objectKeyOrUrl)) {
            throw new ServiceException(FileErrorCode.FILE_KEY_EMPTY);
        }
        MinioClient client = requireClient();
        String objectKey = normalizeObjectKey(objectKeyOrUrl);
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry(properties.getPresignExpireSeconds())
                    .build());
        } catch (Exception e) {
            log.error("[PivotOS] 预签名下载地址生成失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
        }
    }

    /**
     * 归一化对象键：兼容历史落库的完整 fileUrl（{publicUrl}/{bucket}/{objectKey}）与裸对象键。
     */
    private String normalizeObjectKey(String objectKeyOrUrl) {
        String value = objectKeyOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            String bucketPrefix = "/" + properties.getBucket() + "/";
            int idx = value.indexOf(bucketPrefix);
            if (idx >= 0) {
                value = value.substring(idx + bucketPrefix.length());
            } else {
                // 兑底：去掉协议与主机，取 path 部分
                int schemeEnd = value.indexOf("://");
                int pathStart = value.indexOf('/', schemeEnd + 3);
                value = pathStart >= 0 ? value.substring(pathStart + 1) : value;
            }
        }
        // 去掉可能携带的查询串（历史 URL 若带签名参数）
        int q = value.indexOf('?');
        if (q >= 0) {
            value = value.substring(0, q);
        }
        // 去掉前导斜杠
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private MinioClient requireClient() {
        MinioClient client = minioClientProvider.getIfAvailable();
        if (client == null) {
            throw new ServiceException(FileErrorCode.FILE_STORAGE_NOT_CONFIGURED);
        }
        return client;
    }

    /** 提取并校验扩展名（白名单，小写比较） */
    private String extractAndCheckExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new ServiceException(FileErrorCode.FILENAME_EMPTY);
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!properties.getAllowedExtensions().contains(ext)) {
            throw new ServiceException(FileErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return ext;
    }

    /** 桶不存在则创建（惰性 + 双检，MinIO 不可达时抛 4003 上层兜底） */
    private void ensureBucket(MinioClient client) {
        if (bucketChecked) {
            return;
        }
        synchronized (this) {
            if (bucketChecked) {
                return;
            }
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.getBucket()).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                    log.info("[PivotOS] 文件存储桶不存在，已自动创建：{}", properties.getBucket());
                }
                bucketChecked = true;
            } catch (Exception e) {
                log.error("[PivotOS] 存储桶检查/创建失败 bucket={}", properties.getBucket(), e);
                throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
            }
        }
    }
}

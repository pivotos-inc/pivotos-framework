package com.pivotos.file.storage;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.file.api.enums.FileErrorCode;
import com.pivotos.file.config.FileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * S3 兼容存储实现（AWS SDK v2）：覆盖阿里云 OSS / 腾讯云 COS / 华为云 OBS
 * 的 S3 兼容 API（+可选 MinIO S3 模式）。
 * <p>OBS 兼容差异扩展位：目前按标准 S3 语义调用，若实测 OBS 预签名不兼容，
 * 再按 vendor=obs 分支引 esdk-obs-java 独立实现（S25 决议：不提前引依赖）。
 * <p>ensureBucket 与 MinIO 语义不同：公有云账号常无 CreateBucket 权限且桶应预建，
 * 因此只做惰性探测，失败仅告警不阻断（桶不存在时直传会失败并可从日志定位）。
 */
public class S3CompatibleStorageStrategy implements StorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(S3CompatibleStorageStrategy.class);

    private final FileProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    /** 桶存在性惰性确认标记（探测型，见类注释） */
    private volatile boolean bucketChecked = false;

    public S3CompatibleStorageStrategy(FileProperties properties) {
        this.properties = properties;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        Region region = Region.of(properties.effectiveRegion());
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.effectivePathStyle())
                .build();
        URI endpoint = URI.create(properties.getEndpoint());
        this.s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig)
                .build();
    }

    @Override
    public String storageType() {
        return properties.effectiveStorageLabel();
    }

    @Override
    public String presignUpload(String objectKey) {
        ensureBucket();
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build();
            return presigner.presignPutObject(b -> b
                            .signatureDuration(Duration.ofSeconds(properties.getPresignExpireSeconds()))
                            .putObjectRequest(put))
                    .url().toString();
        } catch (Exception e) {
            log.error("[PivotOS] S3 预签名生成失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
        }
    }

    @Override
    public String presignDownload(String objectKey) {
        try {
            GetObjectRequest get = GetObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build();
            return presigner.presignGetObject(b -> b
                            .signatureDuration(Duration.ofSeconds(properties.getPresignExpireSeconds()))
                            .getObjectRequest(get))
                    .url().toString();
        } catch (Exception e) {
            log.error("[PivotOS] S3 预签名下载地址生成失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            // S3 DeleteObject 对不存在对象幂等成功
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("[PivotOS] S3 对象删除失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.FILE_DELETE_FAILED);
        }
    }

    @Override
    public String buildUrl(String objectKey) {
        // path-style：{base}/{bucket}/{key}；virtual-host（公有云缺省）：publicUrl 已含桶域名，直接拼 key
        String base = properties.effectivePublicUrl();
        return properties.effectivePathStyle()
                ? base + "/" + properties.getBucket() + "/" + objectKey
                : base + "/" + objectKey;
    }

    /** 桶惰性探测（不自动创建，见类注释；失败只告警不阻断） */
    private void ensureBucket() {
        if (bucketChecked) {
            return;
        }
        synchronized (this) {
            if (bucketChecked) {
                return;
            }
            try {
                s3Client.headBucket(b -> b.bucket(properties.getBucket()));
            } catch (Exception e) {
                log.warn("[PivotOS] S3 存储桶探测失败（公有云请确认桶已预建）bucket={} cause={}",
                        properties.getBucket(), e.getMessage());
            }
            bucketChecked = true;
        }
    }
}

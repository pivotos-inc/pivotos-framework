package com.pivotos.file.storage;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.file.api.enums.FileErrorCode;
import com.pivotos.file.config.FileProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO 存储实现（S25 前 FileServiceImpl 内嵌逻辑平移，行为零变化）：
 * 惰性双检 ensureBucket + 预签名 PUT/GET + removeObject。
 */
public class MinioStorageStrategy implements StorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageStrategy.class);

    private final FileProperties properties;
    private final MinioClient client;

    /** 桶存在性惰性确认标记（避免每次 presign 都发 HeadBucket） */
    private volatile boolean bucketChecked = false;

    public MinioStorageStrategy(FileProperties properties) {
        this.properties = properties;
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Override
    public String storageType() {
        return "minio";
    }

    @Override
    public String presignUpload(String objectKey) {
        ensureBucket();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry(properties.getPresignExpireSeconds())
                    .build());
        } catch (Exception e) {
            log.error("[PivotOS] 预签名生成失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.PRESIGN_FAILED);
        }
    }

    @Override
    public String presignDownload(String objectKey) {
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

    @Override
    public void delete(String objectKey) {
        try {
            // MinIO removeObject 对不存在对象幂等成功
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("[PivotOS] 对象删除失败 objectKey={}", objectKey, e);
            throw new ServiceException(FileErrorCode.FILE_DELETE_FAILED);
        }
    }

    @Override
    public String buildUrl(String objectKey) {
        return properties.effectivePublicUrl() + "/" + properties.getBucket() + "/" + objectKey;
    }

    /** 桶不存在则创建（惰性 + 双检，MinIO 不可达时抛 4003 上层兜底） */
    private void ensureBucket() {
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

package com.pivotos.file.config;

import com.pivotos.file.storage.MinioStorageStrategy;
import com.pivotos.file.storage.S3CompatibleStorageStrategy;
import com.pivotos.file.storage.StorageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储策略装配：仅当 pivotos.file.endpoint 显式配置时创建
 * （条件装配锚点；未配置时插件端点返回 4020 而非启动失败）。
 * <p>S25 起按 storage-type 二选一装配 StorageStrategy：
 * minio（缺省，存量配置零改动）/ s3（AWS SDK v2，覆盖 OSS/COS/OBS 兼容 API）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
public class FileConfig {

    private static final Logger log = LoggerFactory.getLogger(FileConfig.class);

    /** endpoint 总闸：未配置存储时整组 Strategy Bean 不装配 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pivotos.file", name = "endpoint")
    static class StorageStrategyConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "pivotos.file", name = "storage-type",
                havingValue = "minio", matchIfMissing = true)
        public StorageStrategy minioStorageStrategy(FileProperties properties) {
            log.info("[PivotOS] 文件存储装配：MinIO endpoint={} bucket={}",
                    properties.getEndpoint(), properties.getBucket());
            return new MinioStorageStrategy(properties);
        }

        @Bean
        @ConditionalOnProperty(prefix = "pivotos.file", name = "storage-type", havingValue = "s3")
        public StorageStrategy s3StorageStrategy(FileProperties properties) {
            log.info("[PivotOS] 文件存储装配：S3 兼容（vendor={}）endpoint={} bucket={} region={} pathStyle={}",
                    properties.effectiveStorageLabel(), properties.getEndpoint(),
                    properties.getBucket(), properties.effectiveRegion(), properties.effectivePathStyle());
            return new S3CompatibleStorageStrategy(properties);
        }
    }
}

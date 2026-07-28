package com.pivotos.file.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配：仅当 pivotos.file.endpoint 显式配置时创建
 * （条件装配锚点；未配置时插件端点返回 4020 而非启动失败）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileProperties.class)
public class FileConfig {

    private static final Logger log = LoggerFactory.getLogger(FileConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "pivotos.file", name = "endpoint")
    public MinioClient minioClient(FileProperties properties) {
        log.info("[PivotOS] 文件存储装配：MinIO endpoint={} bucket={}",
                properties.getEndpoint(), properties.getBucket());
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}

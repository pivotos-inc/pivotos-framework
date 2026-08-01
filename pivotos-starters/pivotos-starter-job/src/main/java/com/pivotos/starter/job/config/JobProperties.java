package com.pivotos.starter.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XXL-Job 调度配置属性
 * <p>
 * 前缀: pivotos.job
 */
@Data
@ConfigurationProperties(prefix = "pivotos.job")
public class JobProperties {

    /** 调度中心部署地址（如 http://127.0.0.1:9080/xxl-job-admin），未配则跳过自动装配 */
    private String adminAddresses;

    /** 执行器名称（默认 pivotos-executor） */
    private String appName = "pivotos-executor";

    /** 执行器登记类型：0 自动注册 / 1 手动录入 */
    private int registryType = 0;

    /** 执行器通信 Token（留空不校验） */
    private String accessToken;

    /** 执行器地址（自动注册时可为空） */
    private String address;

    /** 执行器 IP（默认自动探测） */
    private String ip;

    /** 执行器端口（默认 9999） */
    private int port = 9999;

    /** 日志保留天数（默认 30） */
    private int logRetentionDays = 30;

    /** 执行器日志路径 */
    private String logPath = "./logs/xxl-job";

}


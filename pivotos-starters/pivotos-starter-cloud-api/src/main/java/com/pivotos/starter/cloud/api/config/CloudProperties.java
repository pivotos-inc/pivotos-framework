package com.pivotos.starter.cloud.api.config;

import com.pivotos.starter.cloud.api.CloudProvider;
import com.pivotos.starter.cloud.api.context.CloudContextProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微服务通道总配置（{@code pivotos.cloud.*}）。
 *
 * <p>三通道共用这一份配置：{@code provider} 决定装配哪个实现，
 * {@code context} 决定跨进程传播行为（与通道无关）。
 */
@Data
@ConfigurationProperties(prefix = "pivotos.cloud")
public class CloudProperties {

    /**
     * 总开关：false 时所有通道 Bean 均不装配（含入站恢复过滤器）——
     * 单体形态即便 classpath 里有实现 jar 也保持零行为。
     */
    private boolean enabled = true;

    /**
     * 生效通道：local（默认，自研零依赖）/ cloud（Spring Cloud 原生）/ alibaba（Nacos+Sentinel）。
     * 写错不抛异常，回落到 local 并打印 WARN —— 配错不该让应用起不来，但要能被一眼看见。
     */
    private String provider = CloudProvider.LOCAL.value();

    /** 上下文传播（三通道共用） */
    private CloudContextProperties context = new CloudContextProperties();

    /** 解析后的通道枚举（宽松解析，未知值回落 local） */
    public CloudProvider provider() {
        return CloudProvider.of(provider);
    }
}

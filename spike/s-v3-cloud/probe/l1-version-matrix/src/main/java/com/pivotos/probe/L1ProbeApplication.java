package com.pivotos.probe;

import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;

/**
 * V3-S2 · A1-L1 版本矩阵探针启动类。
 *
 * 只做三件事：起 Boot context（web=NONE，不占端口）→ 打印实际生效的版本 →
 * 检查六类关键类是否可加载（Cloud 三面 + Alibaba 三面）。
 *
 * 判定口径：
 *   - 依赖解析失败 / 类加载失败 / context 起不来 → **版本不兼容**（硬结论）
 *   - context 起来但 Nacos 连接报错    → **中间件不可达**（与版本无关，不计为不兼容）
 */
@SpringBootApplication
public class L1ProbeApplication {

    private static final String[] PROBE_CLASSES = {
            // Spring Cloud 侧
            "feign.Feign",
            "org.springframework.cloud.openfeign.FeignClient",
            "org.springframework.cloud.client.loadbalancer.LoadBalancerClient",
            "org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory",
            // Spring Cloud Alibaba 侧
            "com.alibaba.cloud.nacos.NacosDiscoveryProperties",
            "com.alibaba.cloud.nacos.NacosConfigProperties",
            "com.alibaba.csp.sentinel.SphU",
    };

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(L1ProbeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);

        System.out.println("========== L1 PROBE ==========");
        System.out.println("Spring Boot        = " + SpringBootVersion.getVersion());
        System.out.println("Spring Framework   = " + SpringVersion.getVersion());
        System.out.println("----- 关键类可加载性 -----");
        boolean allOk = true;
        for (String cn : PROBE_CLASSES) {
            boolean ok = exists(cn);
            allOk &= ok;
            System.out.println((ok ? "[OK]   " : "[MISS] ") + cn);
        }
        System.out.println("----- 结论 -----");
        System.out.println(allOk
                ? "PROBE_RESULT=COMPATIBLE（版本矩阵成立：依赖解析 + context 启动 + 关键类全部可加载）"
                : "PROBE_RESULT=INCOMPATIBLE（存在类加载失败）");
        System.out.println("==============================");

        ctx.close();
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

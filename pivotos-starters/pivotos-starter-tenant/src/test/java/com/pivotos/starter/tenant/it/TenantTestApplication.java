package com.pivotos.starter.tenant.it;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tenant Starter 集成测试启动类。
 * 只扫描 it 包内的测试夹具（Controller/Mapper），Starter 能力全部经自动配置进入——
 * 即"加依赖 0 改动"的装配路径本身。
 */
@SpringBootApplication(scanBasePackages = "com.pivotos.starter.tenant.it")
public class TenantTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantTestApplication.class, args);
    }
}

package com.pivotos.starter.tenant.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * strict 严格模式测试（库 test_tenant）：
 * 启用后解析不到租户 → 403 + code 1003；解析到 → 正常放行。
 */
@SpringBootTest(classes = TenantTestApplication.class, properties = {
        "pivotos.tenant.enabled=true",
        "pivotos.tenant.mode=column",
        "pivotos.tenant.strict=true",
        "spring.datasource.dynamic.primary=master",
        "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://192.168.50.10:3306/test_tenant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.dynamic.datasource.master.username=root",
        "spring.datasource.dynamic.datasource.master.password=mysql_PBM2cc",
        "spring.datasource.dynamic.datasource.master.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureMockMvc
class TenantColumnStrictTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noTenant_rejected403() throws Exception {
        mockMvc.perform(get("/demo/list"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void withTenant_passes() throws Exception {
        mockMvc.perform(get("/demo/list").header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}

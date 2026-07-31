package com.pivotos.starter.tenant.it;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * column 字段隔离集成测试（库 test_tenant）：
 * 两租户互不可见 / 审计填充 tenant_id / 无上下文单租户放行 /
 * ignore-tables 追加语义（t_platform）/ 内置忽略表（sys_* 在默认值中）。
 * 断言全部基于本次运行标记（幂等复跑，历史经验：独立库非幂等但断言须容忍存量数据）。
 */
@SpringBootTest(classes = TenantTestApplication.class, properties = {
        "pivotos.tenant.enabled=true",
        "pivotos.tenant.mode=column",
        "pivotos.tenant.ignore-tables[0]=t_platform",
        "spring.datasource.dynamic.primary=master",
        "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://175.24.176.176:3306/test_tenant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.dynamic.datasource.master.username=root",
        "spring.datasource.dynamic.datasource.master.password=mysql_DbHEfw",
        "spring.datasource.dynamic.datasource.master.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantColumnIsolationTest {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TITLE_T1 = "T1-" + RUN;
    private static final String TITLE_T2 = "T2-" + RUN;
    private static final String PLATFORM_NAME = "P-" + RUN;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Order(1)
    void create_asTwoTenants() throws Exception {
        createDemo("1", TITLE_T1);
        createDemo("2", TITLE_T2);
    }

    @Test
    @Order(2)
    void list_tenant1_seesOnlyOwn_andAuditFilled() throws Exception {
        JSONArray list = listDemo("1");
        assertThat(titles(list)).contains(TITLE_T1).doesNotContain(TITLE_T2);
        // 审计填充实证：插入行 tenant_id 已被 AuditMetaObjectHandler 写入 1
        assertThat(list.stream()
                .filter(o -> TITLE_T1.equals(((JSONObject) o).getString("title")))
                .map(o -> ((JSONObject) o).getLong("tenantId")))
                .allMatch(t -> t != null && t == 1L);
    }

    @Test
    @Order(3)
    void list_tenant2_seesOnlyOwn() throws Exception {
        JSONArray list = listDemo("2");
        assertThat(titles(list)).contains(TITLE_T2).doesNotContain(TITLE_T1);
    }

    @Test
    @Order(4)
    void list_noTenantHeader_seesAll_singleTenantFallback() throws Exception {
        JSONArray list = listDemo(null);
        assertThat(titles(list)).contains(TITLE_T1, TITLE_T2);
    }

    @Test
    @Order(5)
    void platformTable_ignoreTablesAppend_visibleAcrossTenants() throws Exception {
        mockMvc.perform(post("/platform")
                        .header("X-Tenant-Id", "1")
                        .contentType("application/json")
                        .content("{\"name\":\"" + PLATFORM_NAME + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 无 tenant_id 列的平台表：追加进 ignore-tables 后，任何租户/无租户均可读写
        // （若未放行，MP 拦截器会追加 t_platform.tenant_id 条件直接报 SQL 错误）
        for (String tenant : new String[]{null, "2"}) {
            JSONArray list = listPlatform(tenant);
            assertThat(list.stream().map(o -> ((JSONObject) o).getString("name")))
                    .contains(PLATFORM_NAME);
        }
    }

    private void createDemo(String tenant, String title) throws Exception {
        var request = post("/demo").contentType("application/json")
                .content("{\"title\":\"" + title + "\"}");
        if (tenant != null) {
            request = request.header("X-Tenant-Id", tenant);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private JSONArray listDemo(String tenant) throws Exception {
        return dataArray(get("/demo/list"), tenant);
    }

    private JSONArray listPlatform(String tenant) throws Exception {
        return dataArray(get("/platform/list"), tenant);
    }

    private JSONArray dataArray(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                String tenant) throws Exception {
        if (tenant != null) {
            builder = builder.header("X-Tenant-Id", tenant);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString()).getJSONArray("data");
    }

    private java.util.List<String> titles(JSONArray list) {
        return list.stream().map(o -> ((JSONObject) o).getString("title")).toList();
    }
}

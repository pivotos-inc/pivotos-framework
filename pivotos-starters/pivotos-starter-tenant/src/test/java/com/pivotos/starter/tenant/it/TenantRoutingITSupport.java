package com.pivotos.starter.tenant.it;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由型（schema/datasource）集成测试公共逻辑：
 * 租户 1 无映射 → primary 库；租户 2 有映射 → 第二库。
 * 用纯 JDBC 直连两库实证数据物理落位（中间件实证原则）。
 */
abstract class TenantRoutingITSupport {

    @Autowired
    protected MockMvc mockMvc;

    /** 第二库 JDBC URL（schema/datasource 各指自己的库） */
    protected abstract String secondaryJdbcUrl();

    /** 主库 JDBC URL */
    protected abstract String primaryJdbcUrl();

    protected static void createFixtureTableIfAbsent(String jdbcUrl) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "root", "mysql_DbHEfw");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_tenant_demo ("
                    + "id BIGINT NOT NULL, title VARCHAR(128) NOT NULL,"
                    + "tenant_id BIGINT DEFAULT NULL,"
                    + "create_by BIGINT DEFAULT NULL, create_time DATETIME DEFAULT NULL,"
                    + "update_by BIGINT DEFAULT NULL, update_time DATETIME DEFAULT NULL,"
                    + "deleted TINYINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (id), KEY idx_tenant (tenant_id))"
                    + " ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci");
        }
    }

    protected void createDemo(String tenant, String title) throws Exception {
        var request = post("/demo").contentType("application/json")
                .content("{\"title\":\"" + title + "\"}");
        if (tenant != null) {
            request = request.header("X-Tenant-Id", tenant);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    protected List<String> listTitles(String tenant) throws Exception {
        var builder = get("/demo/list");
        if (tenant != null) {
            builder = builder.header("X-Tenant-Id", tenant);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JSONArray data = JSON.parseObject(result.getResponse().getContentAsString()).getJSONArray("data");
        return data.stream().map(o -> ((JSONObject) o).getString("title")).toList();
    }

    /** 纯 JDBC 直连指定库查询标题（实证数据物理落点） */
    protected List<String> jdbcTitles(String jdbcUrl) throws Exception {
        List<String> titles = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "root", "mysql_DbHEfw");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT title FROM t_tenant_demo")) {
            while (rs.next()) {
                titles.add(rs.getString(1));
            }
        }
        return titles;
    }
}

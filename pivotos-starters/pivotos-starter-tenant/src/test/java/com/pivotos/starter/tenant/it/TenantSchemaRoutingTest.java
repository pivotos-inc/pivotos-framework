package com.pivotos.starter.tenant.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema 模式集成测试：租户 1 → primary（test_tenant_s1），租户 2 → schema-map 路由（test_tenant_s2）。
 * 同实例两 schema 模拟；第二 schema 夹具表由 JDBC 直建（Flyway 只迁移 primary）。
 */
@SpringBootTest(classes = TenantTestApplication.class, properties = {
        "pivotos.tenant.enabled=true",
        "pivotos.tenant.mode=schema",
        "pivotos.tenant.schema-map[2]=s2",
        "spring.datasource.dynamic.primary=master",
        "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://175.24.176.176:3306/test_tenant_s1?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.dynamic.datasource.master.username=root",
        "spring.datasource.dynamic.datasource.master.password=mysql_DbHEfw",
        "spring.datasource.dynamic.datasource.master.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantSchemaRoutingTest extends TenantRoutingITSupport {

    private static final String PRIMARY_URL =
            "jdbc:mysql://175.24.176.176:3306/test_tenant_s1?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String SECONDARY_URL =
            "jdbc:mysql://175.24.176.176:3306/test_tenant_s2?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TITLE_T1 = "S1-" + RUN;
    private static final String TITLE_T2 = "S2-" + RUN;

    @Override
    protected String secondaryJdbcUrl() {
        return SECONDARY_URL;
    }

    @Override
    protected String primaryJdbcUrl() {
        return PRIMARY_URL;
    }

    @BeforeAll
    static void prepareSecondarySchema() throws Exception {
        createFixtureTableIfAbsent(SECONDARY_URL);
    }

    @Test
    @Order(1)
    void create_routesToRespectiveSchema() throws Exception {
        createDemo("1", TITLE_T1);
        createDemo("2", TITLE_T2);
    }

    @Test
    @Order(2)
    void list_eachTenantSeesOwnSchemaOnly() throws Exception {
        assertThat(listTitles("1")).contains(TITLE_T1).doesNotContain(TITLE_T2);
        assertThat(listTitles("2")).contains(TITLE_T2).doesNotContain(TITLE_T1);
    }

    @Test
    @Order(3)
    void jdbcVerify_physicalPlacement() throws Exception {
        assertThat(jdbcTitles(PRIMARY_URL)).contains(TITLE_T1).doesNotContain(TITLE_T2);
        assertThat(jdbcTitles(SECONDARY_URL)).contains(TITLE_T2).doesNotContain(TITLE_T1);
    }
}

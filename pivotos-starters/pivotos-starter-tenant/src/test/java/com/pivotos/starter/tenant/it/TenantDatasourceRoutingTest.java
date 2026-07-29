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
 * datasource 模式集成测试：租户 1 → primary（test_tenant_d1），
 * 租户 2 → datasource-map 路由（test_tenant_d2，同实例不同库模拟独立数据源）。
 */
@SpringBootTest(classes = TenantTestApplication.class, properties = {
        "pivotos.tenant.enabled=true",
        "pivotos.tenant.mode=datasource",
        "pivotos.tenant.datasource-map[2]=d2",
        "spring.datasource.dynamic.primary=master",
        "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://192.168.50.10:3306/test_tenant_d1?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
        "spring.datasource.dynamic.datasource.master.username=root",
        "spring.datasource.dynamic.datasource.master.password=mysql_PBM2cc",
        "spring.datasource.dynamic.datasource.master.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantDatasourceRoutingTest extends TenantRoutingITSupport {

    private static final String PRIMARY_URL =
            "jdbc:mysql://192.168.50.10:3306/test_tenant_d1?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String SECONDARY_URL =
            "jdbc:mysql://192.168.50.10:3306/test_tenant_d2?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TITLE_T1 = "D1-" + RUN;
    private static final String TITLE_T2 = "D2-" + RUN;

    @Override
    protected String secondaryJdbcUrl() {
        return SECONDARY_URL;
    }

    @Override
    protected String primaryJdbcUrl() {
        return PRIMARY_URL;
    }

    @BeforeAll
    static void prepareSecondaryDatasource() throws Exception {
        createFixtureTableIfAbsent(SECONDARY_URL);
    }

    @Test
    @Order(1)
    void create_routesToRespectiveDatasource() throws Exception {
        createDemo("1", TITLE_T1);
        createDemo("2", TITLE_T2);
    }

    @Test
    @Order(2)
    void list_eachTenantSeesOwnDatasourceOnly() throws Exception {
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

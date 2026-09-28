package com.pivotos.system;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S106 C2 租户管理面集成测试：套餐 CRUD → 租户 CRUD → 初始化向导 → 删除守卫 → 清理。
 *
 * <p>测试环境未配 pivotos.tenant.enabled（默认 false），覆盖「关闭时零行为差异」口径：
 * 向导建出的租户管理员可正常登录。enabled=true 的接线链路在 dev 环境实测（见 S106 踩坑记录）。
 *
 * <p>非幂等口径同 SystemIntegrationTest：用例按序执行，末位用例清理全部自建数据。
 */
@SpringBootTest(classes = SystemTestApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String adminToken;
    private static Long packageId;
    private static Long tenantId;
    private static Long wizTenantId;
    private static Long wizAdminUserId;
    private static String wizAdminUsername;

    @Test
    @Order(1)
    void login_admin() throws Exception {
        String body = mockMvc.perform(post("/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        adminToken = JSON.parseObject(body).getJSONObject("data").getString("token");
    }

    @Test
    @Order(2)
    void createPackage_success() throws Exception {
        String body = mockMvc.perform(post("/system/tenant-package")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"packageName\":\"S106集成测试套餐\",\"menuIds\":[1000,1010],\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        packageId = JSON.parseObject(body).getLong("data");
    }

    @Test
    @Order(3)
    void updatePackage_success() throws Exception {
        mockMvc.perform(put("/system/tenant-package")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + packageId
                                + ",\"packageName\":\"S106集成测试套餐改\",\"menuIds\":[1000,1010,1020],\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/system/tenant-package/" + packageId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.packageName").value("S106集成测试套餐改"))
                .andExpect(jsonPath("$.data.menuIds.length()").value(3));

        // 全量替换语义回归：menuIds 不传必须清回 NULL（不限制），而非保留旧值
        mockMvc.perform(put("/system/tenant-package")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + packageId
                                + ",\"packageName\":\"S106集成测试套餐改\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/system/tenant-package/" + packageId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.menuIds").doesNotExist());
    }

    @Test
    @Order(4)
    void createTenant_success() throws Exception {
        String body = mockMvc.perform(post("/system/tenant")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"tenantCode\":\"s106it\",\"tenantName\":\"S106集成测试租户\",\"packageId\":"
                                + packageId + ",\"accountLimit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        tenantId = JSON.parseObject(body).getLong("data");
    }

    @Test
    @Order(5)
    void createTenant_duplicateCode_returns2151() throws Exception {
        mockMvc.perform(post("/system/tenant")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"tenantCode\":\"s106it\",\"tenantName\":\"重复编码租户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2151));
    }

    @Test
    @Order(6)
    void tenantPage_enrichedPackageName() throws Exception {
        mockMvc.perform(get("/system/tenant/page?pageNum=1&pageSize=10&tenantCode=s106it")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].tenantName").value("S106集成测试租户"))
                .andExpect(jsonPath("$.data.list[0].packageName").value("S106集成测试套餐改"))
                .andExpect(jsonPath("$.data.list[0].accountCount").value(0));
    }

    @Test
    @Order(7)
    void wizardInit_success() throws Exception {
        // 管理员用户名带运行唯一后缀：sys_user.uk_username 纯索引不含 deleted，
        // 逻辑删除后用户名仍占用，固定名会导致重复执行时撞唯一键
        wizAdminUsername = "s106wiz" + System.currentTimeMillis() % 100000000L;
        String body = mockMvc.perform(post("/system/tenant/init")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"tenantCode\":\"s106itwiz\",\"tenantName\":\"S106向导租户\",\"packageId\":"
                                + packageId
                                + ",\"accountLimit\":5,\"adminUsername\":\"" + wizAdminUsername
                                + "\",\"adminNickname\":\"向导管理员\",\"adminPassword\":\"Admin@123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JSONObject data = JSON.parseObject(body).getJSONObject("data");
        wizTenantId = data.getLong("tenantId");
        wizAdminUserId = data.getLong("adminUserId");
    }

    @Test
    @Order(8)
    void wizardAdmin_loginSuccess_tenantDisabledZeroDiff() throws Exception {
        // 测试环境 tenant.enabled=false：租户管理员登录与平台用户无差异（零行为差异口径）
        mockMvc.perform(post("/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + wizAdminUsername + "\",\"password\":\"Admin@123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(9)
    void deleteTenant_hasUsers_returns2152() throws Exception {
        mockMvc.perform(delete("/system/tenant/" + wizTenantId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2152));
    }

    @Test
    @Order(10)
    void deletePackage_inUse_returns2157() throws Exception {
        mockMvc.perform(delete("/system/tenant-package/" + packageId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2157));
    }

    @Test
    @Order(11)
    void updateTenant_clearNullableAndRestore() throws Exception {
        // 全量替换语义回归：expireTime 先设值再传 null 必须清回 NULL（覆盖 MP updateById 忽略 null 字段的缺陷）
        mockMvc.perform(put("/system/tenant")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + tenantId
                                + ",\"tenantCode\":\"s106it\",\"tenantName\":\"S106集成测试租户\",\"packageId\":"
                                + packageId + ",\"expireTime\":\"2026-01-01 00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(put("/system/tenant")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + tenantId
                                + ",\"tenantCode\":\"s106it\",\"tenantName\":\"S106集成测试租户\",\"packageId\":"
                                + packageId + ",\"expireTime\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/system/tenant/" + tenantId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.expireTime").doesNotExist());
    }

    @Test
    @Order(12)
    void pageQuery_tenantWithoutPackage_noNpe() throws Exception {
        // 套餐清回 NULL 后，分页富化不能因 Map.of() 空表 get(null) 炸 NPE（dev 实证 1500）
        mockMvc.perform(put("/system/tenant")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + tenantId
                                + ",\"tenantCode\":\"s106it\",\"tenantName\":\"S106集成测试租户\",\"packageId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // tenantCode 走 like，'s106itwiz' 也命中且更晚建会占 list[0]，必须按编码过滤到目标行
        mockMvc.perform(get("/system/tenant/page?pageNum=1&pageSize=10&tenantCode=s106it")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[?(@.tenantCode=='s106it')].packageName").isEmpty());
    }

    @Test
    @Order(13)
    void cleanup_all() throws Exception {
        // 清向导管理员 → 清两个租户 → 清套餐（全链路删除守卫解除后应全部成功）；
        // 向导链路失败时对应 ID 为 null，跳过该步以保证其余清理继续，避免脏数据连锁卡下一轮
        if (wizAdminUserId != null) {
            mockMvc.perform(delete("/system/user/" + wizAdminUserId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        if (wizTenantId != null) {
            mockMvc.perform(delete("/system/tenant/" + wizTenantId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        if (tenantId != null) {
            mockMvc.perform(delete("/system/tenant/" + tenantId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        if (packageId != null) {
            mockMvc.perform(delete("/system/tenant-package/" + packageId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }
}

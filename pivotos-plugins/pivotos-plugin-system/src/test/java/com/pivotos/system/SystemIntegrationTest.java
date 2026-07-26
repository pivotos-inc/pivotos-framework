package com.pivotos.system;

import com.pivotos.system.domain.dto.RoleSaveRequest;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.service.RoleService;
import com.pivotos.system.service.UserService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * system 插件全链路集成测试（S7 验收卡点：登录 → 鉴权 → CRUD）
 *
 * <p>MySQL 8.4 与 Redis 均由 Testcontainers 拉起，Flyway 自动建表灌种子数据。
 */
@SpringBootTest(classes = SystemTestApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserService userService;

    private static String adminToken;
    private static Long createdUserId;

    @Test
    @Order(1)
    void login_wrongPassword_returns2001() throws Exception {
        mockMvc.perform(post("/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @Order(2)
    void login_success_returnsToken() throws Exception {
        String body = mockMvc.perform(post("/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        adminToken = com.alibaba.fastjson2.JSON.parseObject(body)
                .getJSONObject("data").getString("token");
        org.assertj.core.api.Assertions.assertThat(adminToken).isNotBlank();
    }

    @Test
    @Order(3)
    void userPage_noToken_returns1002() throws Exception {
        mockMvc.perform(get("/system/user/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    @Order(4)
    void userPage_withToken_returnsPage() throws Exception {
        mockMvc.perform(get("/system/user/page")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].username").value("admin"));
    }

    @Test
    @Order(5)
    void createUser_thenDuplicate_returns2003() throws Exception {
        String createBody = "{\"username\":\"testuser\",\"nickname\":\"测试用户\","
                + "\"password\":\"test123456\",\"status\":0}";
        String resp = mockMvc.perform(post("/system/user")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        // Long 以 String 序列化（fastjson2 WriteLongAsString）
        createdUserId = Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));

        mockMvc.perform(post("/system/user")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003));
    }

    @Test
    @Order(6)
    void updateUser_success() throws Exception {
        mockMvc.perform(put("/system/user")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"id\":" + createdUserId + ",\"username\":\"testuser\","
                                + "\"nickname\":\"测试用户改\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/system/user/" + createdUserId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("测试用户改"));
    }

    @Test
    @Order(7)
    void getInfo_superAdminWildcard() throws Exception {
        mockMvc.perform(get("/system/auth/getInfo")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                .andExpect(jsonPath("$.data.roles[0]").value("super_admin"))
                .andExpect(jsonPath("$.data.perms[0]").value("*:*:*"));
    }

    @Test
    @Order(8)
    void routers_superAdminGetsAll() throws Exception {
        mockMvc.perform(get("/system/menu/routers")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].meta.title").value("系统管理"))
                .andExpect(jsonPath("$.data[0].children[0].meta.title").value("用户管理"));
    }

    @Test
    @Order(9)
    void limitedUser_noPermission_returns1003() throws Exception {
        // 直接走 Service 造一个无权限角色 + 用户（不经过 API，聚焦鉴权断言）
        RoleSaveRequest roleReq = new RoleSaveRequest();
        roleReq.setRoleName("无权限角色");
        roleReq.setRoleCode("no_perm_role");
        roleReq.setSort(9);
        roleReq.setStatus(0);
        roleReq.setMenuIds(List.of());
        Long roleId = roleService.createRole(roleReq);

        UserSaveRequest userReq = new UserSaveRequest();
        userReq.setUsername("limited");
        userReq.setNickname("受限用户");
        userReq.setPassword("limited123");
        userReq.setStatus(0);
        userReq.setRoleIds(List.of(roleId));
        userService.createUser(userReq);

        String body = mockMvc.perform(post("/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"limited\",\"password\":\"limited123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String limitedToken = com.alibaba.fastjson2.JSON.parseObject(body)
                .getJSONObject("data").getString("token");

        mockMvc.perform(get("/system/user/page")
                        .header("Authorization", limitedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    @Order(10)
    void deleteUser_success() throws Exception {
        mockMvc.perform(delete("/system/user/" + createdUserId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/system/user/" + createdUserId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2004));
    }
}

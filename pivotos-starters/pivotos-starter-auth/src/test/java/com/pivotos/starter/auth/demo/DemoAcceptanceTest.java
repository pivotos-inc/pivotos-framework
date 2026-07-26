package com.pivotos.starter.auth.demo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 步骤 3 总验收：启动 → 异常统一返回 → 分布式锁 → 登录拿 Token → 鉴权拦截 → 上下文可读
 */
@SpringBootTest(classes = DemoApplication.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class DemoAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/demo/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(0, body.getIntValue("code"));
        String token = body.getString("data");
        assertNotNull(token);
        assertFalse(token.isBlank());
        return token;
    }

    @Test
    void loginShouldReturnToken() throws Exception {
        login();
    }

    @Test
    void meWithoutTokenShouldReturn401Body() throws Exception {
        MvcResult result = mockMvc.perform(get("/demo/me"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(1002, body.getIntValue("code"));
    }

    @Test
    void meWithTokenShouldReadContext() throws Exception {
        String token = login();
        MvcResult result = mockMvc.perform(get("/demo/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(0, body.getIntValue("code"));
        JSONObject data = body.getJSONObject("data");
        // fastjson2 全局配置：Long 序列化为 String
        assertEquals("1", data.getString("userId"));
        assertEquals("admin", data.getString("username"));
        assertNotNull(data.getString("traceId"));
    }

    @Test
    void permissionDeniedShouldReturn403Body() throws Exception {
        String token = login();
        MvcResult result = mockMvc.perform(get("/demo/users").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(1003, body.getIntValue("code"));
    }

    @Test
    void serviceExceptionShouldReturnUnifiedBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/demo/error"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(1500, body.getIntValue("code"));
        assertEquals("演示业务异常", body.getString("msg"));
    }

    @Test
    void distributedLockShouldWork() throws Exception {
        MvcResult result = mockMvc.perform(get("/demo/lock").param("key", "demo"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSON.parseObject(result.getResponse().getContentAsString());
        assertEquals(0, body.getIntValue("code"));
        assertTrue(body.getString("data").startsWith("lock-acquired:"));
    }
}

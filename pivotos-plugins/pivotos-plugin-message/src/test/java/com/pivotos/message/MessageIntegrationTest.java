package com.pivotos.message;

import com.alibaba.fastjson2.JSON;
import com.pivotos.message.domain.entity.MsgSendLog;
import com.pivotos.message.mapper.MsgSendLogMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * message 插件全链路集成测试（S13 验收：发消息 → 查询 → 已读 → 重发幂等）
 *
 * <p>中间件连公网服务器 175.24.176.176（2026-07-31 搬迁），独立测试库 test，Flyway 自动建 msg_ 表。
 * 跨插件契约 IUserFacade / 权限 SPI 由 MessageTestApplication 测试替身提供。
 */
@SpringBootTest(classes = MessageTestApplication.class)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MsgSendLogMapper sendLogMapper;

    private static String adminToken;
    private static Long templateId;
    private static Long firstUserMessageId;

    @BeforeAll
    static void login() {
        adminToken = MessageTestApplication.loginToken(1L, "admin");
    }

    @Test
    @Order(1)
    void template_createAndDuplicate() throws Exception {
        String body = "{\"templateCode\":\"it_maintain\",\"templateName\":\"维护通知\","
                + "\"titleTpl\":\"系统维护 {date}\",\"contentTpl\":\"系统将于 {date} 维护\","
                + "\"msgType\":2,\"channel\":\"inbox\",\"status\":0}";
        String resp = mockMvc.perform(post("/message/template")
                        .header("Authorization", adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        templateId = Long.valueOf(JSON.parseObject(resp).getString("data"));

        mockMvc.perform(post("/message/template")
                        .header("Authorization", adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3021));
    }

    @Test
    @Order(2)
    void template_pageAndGet() throws Exception {
        mockMvc.perform(get("/message/template/page?templateCode=it_maintain")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/message/template/" + templateId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateName").value("维护通知"));
    }

    @Test
    @Order(3)
    void send_direct_inbox() throws Exception {
        mockMvc.perform(post("/message/manage/send")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"title\":\"版本发布\",\"content\":\"v0.6.0 已发布\","
                                + "\"msgType\":1,\"receiverIds\":[1,2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(4)
    void userPage_unread_one() throws Exception {
        String resp = mockMvc.perform(get("/message/user/page?readStatus=0")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].title").value("版本发布"))
                .andReturn().getResponse().getContentAsString();
        firstUserMessageId = Long.valueOf(JSON.parseObject(resp).getJSONObject("data")
                .getJSONArray("list").getJSONObject(0).getString("userMessageId"));
        mockMvc.perform(get("/message/user/unread-count")
                        .header("Authorization", adminToken))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @Order(5)
    void markRead_thenUnreadZero() throws Exception {
        mockMvc.perform(put("/message/user/read/" + firstUserMessageId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/message/user/unread-count")
                        .header("Authorization", adminToken))
                .andExpect(jsonPath("$.data").value(0));
        // 越权/不存在：user 2 的 token 读 user 1 的消息 → 3002
        String otherToken = MessageTestApplication.loginToken(2L, "zhangsan");
        mockMvc.perform(put("/message/user/read/" + firstUserMessageId)
                        .header("Authorization", otherToken))
                .andExpect(jsonPath("$.code").value(3002));
    }

    @Test
    @Order(6)
    void send_withTemplate_renders() throws Exception {
        mockMvc.perform(post("/message/manage/send")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"templateCode\":\"it_maintain\",\"receiverIds\":[1],"
                                + "\"bizType\":\"ops\",\"bizId\":\"MT-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 模板无参数渲染，占位符保留；类型取模板默认 2
        mockMvc.perform(get("/message/user/page")
                        .header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @Order(7)
    void readAll_marksEverything() throws Exception {
        mockMvc.perform(put("/message/user/read-all")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(get("/message/user/unread-count")
                        .header("Authorization", adminToken))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @Order(8)
    void managePage_receiverCount() throws Exception {
        mockMvc.perform(get("/message/manage/page?title=版本发布")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].receiverCount").value(3));
    }

    @Test
    @Order(9)
    void send_sms_writesSendLog() throws Exception {
        mockMvc.perform(post("/message/manage/send")
                        .header("Authorization", adminToken)
                        .contentType("application/json")
                        .content("{\"title\":\"短信验证码通知\",\"content\":\"您的验证码 8888\","
                                + "\"channel\":\"sms\",\"receiverIds\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 渠道异步投递（虚拟线程），轮询等待留痕落库
        List<MsgSendLog> logs = List.of();
        for (int i = 0; i < 50; i++) {
            logs = sendLogMapper.selectList(null).stream()
                    .filter(l -> "sms".equals(l.getChannel())
                            && "短信验证码通知".equals(l.getTitle()))
                    .toList();
            if (logs.size() == 2) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getReceiver()).startsWith("138");
        // 短信不落用户消息表：未读数不增加
        mockMvc.perform(get("/message/user/unread-count")
                        .header("Authorization", adminToken))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @Order(10)
    void noToken_returns1002() throws Exception {
        mockMvc.perform(get("/message/user/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }
}

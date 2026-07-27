package com.pivotos.message;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.facade.IMessageFacade;
import com.pivotos.starter.core.context.LoginContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMessageFacade 行为契约测试
 *
 * <p>只面向契约编程（按接口注入），断言契约承诺的行为：
 * 未来微服务远程实现替换本地实现时，本测试应原样通过（0 改动比对标准）。
 */
@SpringBootTest(classes = MessageTestApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageFacadeContractTest {

    /** 契约注入点：消费方永远只见接口 */
    @Autowired
    private IMessageFacade messageFacade;

    private static final Long CONTRACT_USER = 3L;

    @Test
    @Order(1)
    void send_returnsMessageId() {
        MessageSendCmd cmd = new MessageSendCmd();
        cmd.setTitle("契约测试消息");
        cmd.setContent("面向接口编程");
        cmd.setMsgType(1);
        cmd.setReceiverIds(List.of(CONTRACT_USER));
        Long messageId = messageFacade.send(cmd);
        assertThat(messageId).isNotNull();
    }

    @Test
    @Order(2)
    void countUnread_and_pageByUser() {
        long unread = messageFacade.countUnread(CONTRACT_USER);
        assertThat(unread).isGreaterThanOrEqualTo(1);

        PageResult<MessageDTO> page = messageFacade.pageByUser(CONTRACT_USER, new MessagePageQuery());
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(page.getList().get(0).getUserMessageId()).isNotNull();
        assertThat(page.getList().get(0).getTitle()).isNotBlank();
    }

    @Test
    @Order(3)
    void markRead_viaLoginContext() {
        MessagePageQuery unreadQuery = new MessagePageQuery();
        unreadQuery.setReadStatus(0);
        long unreadBefore = messageFacade.pageByUser(CONTRACT_USER, unreadQuery).getTotal();
        Long userMessageId = messageFacade.pageByUser(CONTRACT_USER, unreadQuery)
                .getList().get(0).getUserMessageId();
        // 契约实现内部从 LoginContext 取操作人：测试用 ScopedValue 模拟请求上下文绑定
        ScopedValue.where(LoginContext.KEY, new LoginUser(CONTRACT_USER, "lisi", "sys-user", null))
                .run(() -> messageFacade.markRead(userMessageId));

        long unreadAfter = messageFacade.pageByUser(CONTRACT_USER, unreadQuery).getTotal();
        assertThat(unreadAfter).isEqualTo(unreadBefore - 1);
    }

    @Test
    @Order(4)
    void markAllRead_returnsAffectedRows() {
        MessageSendCmd cmd = new MessageSendCmd();
        cmd.setTitle("契约-批量已读");
        cmd.setContent("批量已读验证");
        cmd.setReceiverIds(List.of(CONTRACT_USER));
        messageFacade.send(cmd);

        int affected = messageFacade.markAllRead(CONTRACT_USER);
        assertThat(affected).isGreaterThanOrEqualTo(1);
        assertThat(messageFacade.countUnread(CONTRACT_USER)).isZero();
    }
}

package com.pivotos.message.channel;

import com.pivotos.message.api.enums.MessageChannelEnum;
import com.pivotos.message.domain.entity.MsgMessage;

import java.util.List;

/**
 * 消息外发渠道抽象
 *
 * <p>站内信（INBOX）由 MessageService 直接落库，不走本接口；
 * 短信/邮件等外发渠道实现本接口，经 msg_send_log 留痕。
 * 新渠道（钉钉/企微/WebSocket 推送…）新增实现即可，发送主链路 0 改动。
 */
public interface MessageChannel {

    /** 渠道标识 */
    MessageChannelEnum channel();

    /**
     * 外发投递（实现方自行决定同步/异步；异步必须走 contextExecutor，禁止裸线程）
     *
     * @param message     消息主记录
     * @param receiverIds 接收人用户 ID 集合（已经 IUserFacade 校验）
     */
    void dispatch(MsgMessage message, List<Long> receiverIds);
}

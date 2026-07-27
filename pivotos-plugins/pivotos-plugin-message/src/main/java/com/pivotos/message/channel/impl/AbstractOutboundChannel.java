package com.pivotos.message.channel.impl;

import com.pivotos.message.channel.MessageChannel;
import com.pivotos.message.constant.MessageConstants;
import com.pivotos.message.domain.entity.MsgMessage;
import com.pivotos.message.domain.entity.MsgSendLog;
import com.pivotos.message.mapper.MsgSendLogMapper;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 外发渠道基类：异步（contextExecutor 虚拟线程 + 上下文传播）逐接收人投递并写 msg_send_log。
 *
 * <p>当前短信/邮件网关未接入，实现仅做"留痕"：接收标识取自 system 契约 IUserFacade
 * 返回的联系方式，网关接入后在 {@link #doSend} 中替换为真实调用，主链路 0 改动。
 */
@RequiredArgsConstructor
public abstract class AbstractOutboundChannel implements MessageChannel {

    private final MsgSendLogMapper sendLogMapper;
    private final IUserFacade userFacade;
    @Qualifier("contextExecutor")
    private final ExecutorService contextExecutor;

    @Override
    public void dispatch(MsgMessage message, List<Long> receiverIds) {
        Map<Long, UserDTO> userMap = userFacade.listByIds(receiverIds).stream()
                .collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        for (Long receiverId : receiverIds) {
            UserDTO user = userMap.get(receiverId);
            // ContextExecutor 包装的虚拟线程：登录/租户/链路上下文自动带入异步线程
            contextExecutor.submit(() -> doSend(message, user));
        }
    }

    /** 单接收人投递 + 留痕 */
    private void doSend(MsgMessage message, UserDTO user) {
        MsgSendLog log = new MsgSendLog();
        log.setMessageId(message.getId());
        log.setChannel(channel().getCode());
        log.setReceiver(user == null ? "" : contactOf(user));
        log.setTitle(message.getTitle());
        log.setSendStatus(MessageConstants.SEND_SUCCESS);
        log.setErrorMsg("");
        sendLogMapper.insert(log);
    }

    /** 从用户 DTO 取本渠道的接收标识（手机号/邮箱） */
    protected abstract String contactOf(UserDTO user);
}

package com.pivotos.message.channel.impl;

import com.pivotos.message.api.enums.MessageChannelEnum;
import com.pivotos.message.mapper.MsgSendLogMapper;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 邮件渠道（留痕实现）
 *
 * <p>真实邮件网关（SMTP/SES）接入时仅需替换 doSend 调用，契约与主链路不变。
 */
@Component
public class EmailChannel extends AbstractOutboundChannel {

    public EmailChannel(MsgSendLogMapper sendLogMapper, IUserFacade userFacade,
                        @Qualifier("contextExecutor") ExecutorService contextExecutor) {
        super(sendLogMapper, userFacade, contextExecutor);
    }

    @Override
    public MessageChannelEnum channel() {
        return MessageChannelEnum.EMAIL;
    }

    @Override
    protected String contactOf(UserDTO user) {
        return user.getEmail() == null ? "" : user.getEmail();
    }
}

package com.pivotos.message.api.facade;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;

/**
 * 消息门面契约
 *
 * <p>跨 Plugin 发送/读取消息的唯一入口（禁止跨 Plugin 直接依赖实现模块）。
 * 单体部署由 message 插件提供本地实现；微服务形态可替换为远程实现，消费方 0 改动。
 *
 * <p>契约稳定性要求：本接口一经发布变更成本高，新增能力优先加方法并保留旧方法
 * （旧方法标注 {@code @Deprecated} 并行一个周期），禁止修改既有方法签名。
 */
public interface IMessageFacade {

    /**
     * 发送消息（按命令中的渠道分发：站内信落库 msg_message + msg_user_message，
     * 短信/邮件记录 msg_send_log），发送成功后发布 {@code MessageSentEvent}
     *
     * @param cmd 发送命令（标题/内容/渠道/接收人集合）
     * @return 消息 ID
     */
    Long send(MessageSendCmd cmd);

    /**
     * 按用户分页查询其收到的消息
     *
     * @param userId 用户 ID
     * @param query  分页与过滤条件（已读状态 / 消息类型）
     * @return 用户消息分页
     */
    PageResult<MessageDTO> pageByUser(Long userId, MessagePageQuery query);

    /**
     * 将一条用户消息标记为已读
     *
     * @param userMessageId 用户消息 ID（msg_user_message 主键）
     */
    void markRead(Long userMessageId);

    /**
     * 将某用户全部未读消息标记为已读
     *
     * @param userId 用户 ID
     * @return 实际置为已读的条数
     */
    int markAllRead(Long userId);

    /**
     * 统计某用户未读消息数
     *
     * @param userId 用户 ID
     * @return 未读条数
     */
    long countUnread(Long userId);
}

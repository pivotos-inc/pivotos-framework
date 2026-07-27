package com.pivotos.message.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.domain.dto.MessageManageQuery;
import com.pivotos.message.domain.vo.MessageManageVO;

/** 消息服务（站内信核心逻辑） */
public interface MessageService {

    /**
     * 发送消息：校验接收人（经 IUserFacade）→ 落库 → 发布 MessageSentEvent。
     * 重发幂等由 msg_user_message.uk_message_user 兜底。
     *
     * @param cmd 发送命令
     * @return 消息 ID
     */
    Long send(MessageSendCmd cmd);

    /** 按用户分页查询其收到的消息（最新在前） */
    PageResult<MessageDTO> pageByUser(Long userId, MessagePageQuery query);

    /**
     * 标记一条用户消息已读（校验归属，防越权改他人消息）
     *
     * @param userMessageId 用户消息 ID
     * @param userId        操作人用户 ID
     */
    void markRead(Long userMessageId, Long userId);

    /** 全部置为已读，返回实际更新条数 */
    int markAllRead(Long userId);

    /** 未读数 */
    long countUnread(Long userId);

    /** 后台全量分页（含接收人数统计） */
    PageResult<MessageManageVO> pageAll(MessageManageQuery query);

    /** 后台消息详情 */
    MessageManageVO getManage(Long id);
}

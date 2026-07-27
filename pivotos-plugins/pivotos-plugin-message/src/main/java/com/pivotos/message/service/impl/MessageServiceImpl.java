package com.pivotos.message.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.enums.MessageChannelEnum;
import com.pivotos.message.api.enums.MessageErrorCode;
import com.pivotos.message.api.event.MessageSentEvent;
import com.pivotos.message.constant.MessageConstants;
import com.pivotos.message.domain.dto.MessageManageQuery;
import com.pivotos.message.domain.entity.MsgMessage;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.entity.MsgUserMessage;
import com.pivotos.message.domain.vo.MessageManageVO;
import com.pivotos.message.mapper.MsgMessageMapper;
import com.pivotos.message.mapper.MsgUserMessageMapper;
import com.pivotos.message.service.MessageService;
import com.pivotos.message.service.TemplateService;
import com.pivotos.message.support.PageUtils;
import com.pivotos.system.api.facade.IUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消息服务实现（站内信核心逻辑）
 *
 * <p>跨插件协作：接收人校验注入 system 契约 IUserFacade（禁止 import system 实现包）。
 * 短信/邮件外发渠道由 channel 包扩展（commit 6 接入）。
 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MsgMessageMapper, MsgMessage> implements MessageService {

    private final MsgUserMessageMapper userMessageMapper;
    private final TemplateService templateService;
    private final IUserFacade userFacade;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long send(MessageSendCmd cmd) {
        // 1. 内容解析：模板渲染优先，否则直接用 title/content
        String title = cmd.getTitle();
        String content = cmd.getContent();
        Integer msgType = cmd.getMsgType();
        String channel = cmd.getChannel();
        if (StringUtils.hasText(cmd.getTemplateCode())) {
            MsgTemplate template = templateService.requireEnabledByCode(cmd.getTemplateCode());
            title = templateService.render(template.getTitleTpl(), cmd.getParams());
            content = templateService.render(template.getContentTpl(), cmd.getParams());
            if (msgType == null) {
                msgType = template.getMsgType();
            }
            if (!StringUtils.hasText(channel)) {
                channel = template.getChannel();
            }
        }
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new ServiceException(MessageErrorCode.MESSAGE_NOT_FOUND.getCode(), "消息标题与内容不能为空");
        }

        // 2. 渠道校验（短信/邮件由 channel 包扩展后放行）
        String finalChannel = StringUtils.hasText(channel) ? channel : MessageChannelEnum.INBOX.getCode();
        MessageChannelEnum channelEnum = MessageChannelEnum.of(finalChannel);
        if (channelEnum == null) {
            throw new ServiceException(MessageErrorCode.CHANNEL_UNSUPPORTED);
        }

        // 3. 接收人校验（跨插件走 system 契约，不存在的用户自动忽略）
        if (cmd.getReceiverIds() == null || cmd.getReceiverIds().isEmpty()) {
            throw new ServiceException(MessageErrorCode.MESSAGE_RECEIVER_EMPTY);
        }
        List<Long> receiverIds = cmd.getReceiverIds().stream().filter(Objects::nonNull).distinct().toList();
        List<Long> validIds = userFacade.listByIds(receiverIds).stream()
                .map(com.pivotos.system.api.dto.UserDTO::getId).toList();
        if (validIds.isEmpty()) {
            throw new ServiceException(MessageErrorCode.MESSAGE_RECEIVER_EMPTY);
        }

        // 4. 落库（主表 + 用户消息表；uk_message_user 为重发幂等兜底）
        MsgMessage message = new MsgMessage();
        message.setTitle(title);
        message.setContent(content);
        message.setMsgType(msgType == null ? MessageConstants.TYPE_NOTICE : msgType);
        message.setBizType(cmd.getBizType() == null ? "" : cmd.getBizType());
        message.setBizId(cmd.getBizId() == null ? "" : cmd.getBizId());
        save(message);

        if (MessageChannelEnum.INBOX == channelEnum) {
            LocalDateTime now = LocalDateTime.now();
            List<MsgUserMessage> rows = validIds.stream().map(userId -> {
                MsgUserMessage row = new MsgUserMessage();
                row.setMessageId(message.getId());
                row.setUserId(userId);
                row.setReadStatus(MessageConstants.READ_NO);
                row.setReadTime(null);
                row.setCreateTime(now);
                return row;
            }).toList();
            rows.forEach(userMessageMapper::insert);
        }

        // 5. 发布事件（移动端推送 Starter 后续消费）
        eventPublisher.publishEvent(new MessageSentEvent(message.getId(), message.getTitle(),
                message.getMsgType(), finalChannel, validIds, LocalDateTime.now()));
        return message.getId();
    }

    @Override
    public PageResult<MessageDTO> pageByUser(Long userId, MessagePageQuery query) {
        Page<MsgUserMessage> page = userMessageMapper.selectPage(PageUtils.toMpPage(query),
                Wrappers.<MsgUserMessage>lambdaQuery()
                        .eq(MsgUserMessage::getUserId, userId)
                        .eq(query.getReadStatus() != null, MsgUserMessage::getReadStatus, query.getReadStatus())
                        .orderByDesc(MsgUserMessage::getId));
        List<MsgUserMessage> records = page.getRecords();
        if (records.isEmpty()) {
            return PageUtils.toPageResult(page, List.of());
        }
        List<Long> messageIds = records.stream().map(MsgUserMessage::getMessageId).toList();
        Map<Long, MsgMessage> messageMap = listByIds(messageIds).stream()
                .collect(Collectors.toMap(MsgMessage::getId, Function.identity()));
        List<MessageDTO> list = records.stream()
                .filter(row -> messageMap.containsKey(row.getMessageId()))
                .filter(row -> query.getMsgType() == null
                        || query.getMsgType().equals(messageMap.get(row.getMessageId()).getMsgType()))
                .map(row -> toDTO(row, messageMap.get(row.getMessageId())))
                .toList();
        return PageUtils.toPageResult(page, list);
    }

    @Override
    public void markRead(Long userMessageId, Long userId) {
        MsgUserMessage row = userMessageMapper.selectById(userMessageId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new ServiceException(MessageErrorCode.USER_MESSAGE_NOT_FOUND);
        }
        if (MessageConstants.READ_YES == row.getReadStatus()) {
            return;
        }
        MsgUserMessage update = new MsgUserMessage();
        update.setId(userMessageId);
        update.setReadStatus(MessageConstants.READ_YES);
        update.setReadTime(LocalDateTime.now());
        userMessageMapper.updateById(update);
    }

    @Override
    public int markAllRead(Long userId) {
        MsgUserMessage update = new MsgUserMessage();
        update.setReadStatus(MessageConstants.READ_YES);
        update.setReadTime(LocalDateTime.now());
        return userMessageMapper.update(update, Wrappers.<MsgUserMessage>lambdaQuery()
                .eq(MsgUserMessage::getUserId, userId)
                .eq(MsgUserMessage::getReadStatus, MessageConstants.READ_NO));
    }

    @Override
    public long countUnread(Long userId) {
        return userMessageMapper.selectCount(Wrappers.<MsgUserMessage>lambdaQuery()
                .eq(MsgUserMessage::getUserId, userId)
                .eq(MsgUserMessage::getReadStatus, MessageConstants.READ_NO));
    }

    @Override
    public PageResult<MessageManageVO> pageAll(MessageManageQuery query) {
        Page<MsgMessage> page = page(PageUtils.toMpPage(query), Wrappers.<MsgMessage>lambdaQuery()
                .like(StringUtils.hasText(query.getTitle()), MsgMessage::getTitle, query.getTitle())
                .eq(query.getMsgType() != null, MsgMessage::getMsgType, query.getMsgType())
                .eq(StringUtils.hasText(query.getBizType()), MsgMessage::getBizType, query.getBizType())
                .orderByDesc(MsgMessage::getId));
        List<MsgMessage> records = page.getRecords();
        if (records.isEmpty()) {
            return PageUtils.toPageResult(page, List.of());
        }
        Map<Long, Long> countMap = receiverCounts(records.stream().map(MsgMessage::getId).toList());
        List<MessageManageVO> list = records.stream()
                .map(message -> toManageVO(message, countMap.getOrDefault(message.getId(), 0L)))
                .toList();
        return PageUtils.toPageResult(page, list);
    }

    @Override
    public MessageManageVO getManage(Long id) {
        MsgMessage message = getById(id);
        if (message == null) {
            throw new ServiceException(MessageErrorCode.MESSAGE_NOT_FOUND);
        }
        Long count = receiverCounts(List.of(id)).getOrDefault(id, 0L);
        return toManageVO(message, count);
    }

    /** 批量统计每条消息的接收人数 */
    private Map<Long, Long> receiverCounts(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return userMessageMapper.selectMaps(Wrappers.<MsgUserMessage>query()
                        .select("message_id AS messageId, COUNT(*) AS cnt")
                        .in("message_id", messageIds)
                        .groupBy("message_id"))
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("messageId")).longValue(),
                        row -> ((Number) row.get("cnt")).longValue()));
    }

    private MessageManageVO toManageVO(MsgMessage message, Long receiverCount) {
        MessageManageVO vo = new MessageManageVO();
        vo.setId(message.getId());
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        vo.setMsgType(message.getMsgType());
        vo.setBizType(message.getBizType());
        vo.setBizId(message.getBizId());
        vo.setReceiverCount(receiverCount);
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }

    private MessageDTO toDTO(MsgUserMessage row, MsgMessage message) {
        MessageDTO dto = new MessageDTO();
        dto.setId(message.getId());
        dto.setUserMessageId(row.getId());
        dto.setTitle(message.getTitle());
        dto.setContent(message.getContent());
        dto.setMsgType(message.getMsgType());
        dto.setBizType(message.getBizType());
        dto.setBizId(message.getBizId());
        dto.setReadStatus(row.getReadStatus());
        dto.setReadTime(row.getReadTime());
        dto.setCreateTime(row.getCreateTime());
        return dto;
    }
}

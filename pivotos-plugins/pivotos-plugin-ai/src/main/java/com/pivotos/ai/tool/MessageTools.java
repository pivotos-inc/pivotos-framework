package com.pivotos.ai.tool;

import com.alibaba.fastjson2.JSON;
import com.pivotos.ai.enums.ToolType;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.enums.MessageChannelEnum;
import com.pivotos.message.api.facade.IMessageFacade;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息类 AI 工具（S99 A2 首批业务工具）：查通知（只读）+ 发站内通知（写操作）。
 *
 * <p>供给面为消息门面 {@link IMessageFacade}（跨插件只依赖 -api，与
 * WorkflowQueryTools 的工作流门面口径一致）；门面未装配时降级返回不可用说明。
 *
 * <p>写操作工具（发站内通知）按 S98 二次确认协议：@AiToolMeta(type=WRITE) +
 * 方法签名显式 boolean confirm，confirm=true 才真实发送，否则守卫层返回预检说明。
 */
@Component
@RequiredArgsConstructor
public class MessageTools {

    /** 工具侧分页条数上限（控制返回给模型的上下文体积） */
    private static final int MAX_PAGE_SIZE = 20;

    /** 单次群发收件人上限（防模型幻觉一次性轰炸全员） */
    private static final int MAX_RECEIVERS = 20;

    /** 标题长度上限（与站内信前端表单口径一致） */
    private static final int MAX_TITLE_LEN = 100;

    /** 内容长度上限（与站内信前端表单口径一致） */
    private static final int MAX_CONTENT_LEN = 1000;

    /** 消息门面（可选依赖：message 插件未装配时降级返回，与工作流门面降级口径一致） */
    private final ObjectProvider<IMessageFacade> messageFacadeProvider;

    @Tool(name = "queryMyMessages",
            description = "分页查询当前登录用户收到的站内消息（只读）：返回未读数、总数与消息列表（标题/内容/类型/已读状态）。"
                    + "confirm 参数仅用于平台二次确认协议占位（只读工具传 false 或忽略均可）。")
    public String queryMyMessages(
            @ToolParam(description = "页码，从 1 开始") int pageNum,
            @ToolParam(description = "每页条数（最大 20）") int pageSize,
            @ToolParam(description = "二次确认占位参数，只读工具传 false 即可") boolean confirm) {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            return "当前无登录用户上下文，无法执行本操作。";
        }
        IMessageFacade facade = messageFacadeProvider.getIfAvailable();
        if (facade == null) {
            return "消息模块未装配，本工具暂不可用。";
        }
        MessagePageQuery query = new MessagePageQuery();
        query.setPageNum(pageNum < 1 ? 1 : pageNum);
        query.setPageSize(cap(pageSize));
        PageResult<MessageDTO> page = facade.pageByUser(userId, query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", facade.countUnread(userId));
        result.put("total", page.getTotal());
        result.put("pageNum", page.getPageNum());
        result.put("list", page.getList());
        return JSON.toJSONString(result);
    }

    @Tool(name = "sendInboxMessage",
            description = "向指定用户发送站内通知（写操作）：receiverUserIds 为逗号分隔的用户 ID（数字），最多 20 人。"
                    + "二次确认协议：confirm=false 仅预检不发送；请向用户复述标题、内容与收件人，获得明确同意后以 confirm=true 重新调用才真实发送。")
    @AiToolMeta(type = ToolType.WRITE, confirmRequired = true)
    public String sendInboxMessage(
            @ToolParam(description = "通知标题（不超过 100 字）") String title,
            @ToolParam(description = "通知内容（不超过 1000 字）") String content,
            @ToolParam(description = "收件人用户 ID，逗号分隔，如 1,2,3") String receiverUserIds,
            @ToolParam(description = "二次确认标记：true 真实发送，false 仅预检") boolean confirm) {
        Long senderId = LoginContext.getUserId();
        if (senderId == null) {
            return "当前无登录用户上下文，无法执行本操作。";
        }
        IMessageFacade facade = messageFacadeProvider.getIfAvailable();
        if (facade == null) {
            return "消息模块未装配，本工具暂不可用。";
        }
        validate(title, content);
        List<Long> receivers = parseReceivers(receiverUserIds);
        MessageSendCmd cmd = new MessageSendCmd();
        cmd.setTitle(title);
        cmd.setContent(content);
        cmd.setChannel(MessageChannelEnum.INBOX.getCode());
        cmd.setMsgType(1);
        cmd.setBizType("ai.tool");
        cmd.setReceiverIds(receivers);
        Long messageId = facade.send(cmd);
        return "站内通知发送成功：消息 ID=" + messageId + "，收件人数=" + receivers.size() + "。";
    }

    private void validate(String title, String content) {
        if (!StringUtils.hasText(title)) {
            throw new ServiceException("通知标题不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new ServiceException("通知内容不能为空");
        }
        if (title.length() > MAX_TITLE_LEN) {
            throw new ServiceException("通知标题过长（上限 " + MAX_TITLE_LEN + " 字）");
        }
        if (content.length() > MAX_CONTENT_LEN) {
            throw new ServiceException("通知内容过长（上限 " + MAX_CONTENT_LEN + " 字）");
        }
    }

    /**
     * 解析逗号分隔的收件人用户 ID；非数字/超限一律拒绝（从严，防模型幻觉群发）
     */
    private List<Long> parseReceivers(String receiverUserIds) {
        if (!StringUtils.hasText(receiverUserIds)) {
            throw new ServiceException("收件人不能为空");
        }
        List<Long> receivers = new ArrayList<>();
        for (String part : receiverUserIds.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                receivers.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new ServiceException("收件人必须是数字用户 ID（逗号分隔），无法解析：" + trimmed);
            }
        }
        if (receivers.isEmpty()) {
            throw new ServiceException("收件人不能为空");
        }
        if (receivers.size() > MAX_RECEIVERS) {
            throw new ServiceException("单次最多发送给 " + MAX_RECEIVERS + " 位收件人");
        }
        return receivers.stream().distinct().toList();
    }

    private int cap(int pageSize) {
        if (pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}

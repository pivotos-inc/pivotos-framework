package com.pivotos.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.dto.MessageDTO;
import com.pivotos.message.api.dto.MessagePageQuery;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.api.enums.MessageChannelEnum;
import com.pivotos.message.api.event.MessageSentEvent;
import com.pivotos.message.channel.MessageChannel;
import com.pivotos.message.domain.dto.MessageManageQuery;
import com.pivotos.message.domain.entity.MsgMessage;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.entity.MsgUserMessage;
import com.pivotos.message.domain.vo.MessageManageVO;
import com.pivotos.message.mapper.MsgMessageMapper;
import com.pivotos.message.mapper.MsgUserMessageMapper;
import com.pivotos.message.service.TemplateService;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MessageServiceImpl 单元测试（Mapper/Facade/事件全 Mock） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceImplTest {

    @Mock
    private MsgMessageMapper messageMapper;
    @Mock
    private MsgUserMessageMapper userMessageMapper;
    @Mock
    private TemplateService templateService;
    @Mock
    private IUserFacade userFacade;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MessageChannel smsChannel;

    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(userMessageMapper, templateService, userFacade,
                eventPublisher, List.of(smsChannel));
        ReflectionTestUtils.setField(service, "baseMapper", messageMapper);
        doAnswer(inv -> {
            inv.getArgument(0, MsgMessage.class).setId(100L);
            return 1;
        }).when(messageMapper).insert(any(MsgMessage.class));
        when(userFacade.listByIds(anyCollection())).thenAnswer(inv -> {
            List<Long> ids = List.copyOf(inv.getArgument(0));
            return ids.stream().filter(id -> id <= 3L).map(id -> {
                UserDTO u = new UserDTO();
                u.setId(id);
                return u;
            }).toList();
        });
    }

    private MessageSendCmd directCmd() {
        MessageSendCmd cmd = new MessageSendCmd();
        cmd.setTitle("系统维护通知");
        cmd.setContent("今晚 22:00 停机维护");
        cmd.setReceiverIds(List.of(1L, 2L));
        return cmd;
    }

    // ---------- send ----------

    @Test
    void send_direct_inbox_success() {
        Long id = service.send(directCmd());
        assertThat(id).isEqualTo(100L);
        // 站内信：每个有效接收人一条用户消息
        verify(userMessageMapper, times(2)).insert(any(MsgUserMessage.class));
        // 事件已发布
        ArgumentCaptor<MessageSentEvent> captor = ArgumentCaptor.forClass(MessageSentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo(100L);
        assertThat(captor.getValue().getReceiverIds()).containsExactly(1L, 2L);
    }

    @Test
    void send_template_rendersAndDefaults() {
        MsgTemplate tpl = new MsgTemplate();
        tpl.setTemplateCode("maintain");
        tpl.setTitleTpl("维护 {date}");
        tpl.setContentTpl("内容 {date}");
        tpl.setMsgType(2);
        tpl.setChannel("inbox");
        when(templateService.requireEnabledByCode("maintain")).thenReturn(tpl);
        when(templateService.render("维护 {date}", Map.of("date", "周六"))).thenReturn("维护 周六");
        when(templateService.render("内容 {date}", Map.of("date", "周六"))).thenReturn("内容 周六");

        MessageSendCmd cmd = new MessageSendCmd();
        cmd.setTemplateCode("maintain");
        cmd.setParams(Map.of("date", "周六"));
        cmd.setReceiverIds(List.of(1L));
        service.send(cmd);

        ArgumentCaptor<MsgMessage> captor = ArgumentCaptor.forClass(MsgMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("维护 周六");
        assertThat(captor.getValue().getMsgType()).isEqualTo(2);
    }

    @Test
    void send_blankTitle_throws() {
        MessageSendCmd cmd = directCmd();
        cmd.setTitle(" ");
        assertThatThrownBy(() -> service.send(cmd)).isInstanceOf(ServiceException.class);
    }

    @Test
    void send_emptyReceivers_throws3003() {
        MessageSendCmd cmd = directCmd();
        cmd.setReceiverIds(List.of());
        assertThatThrownBy(() -> service.send(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3003);
    }

    @Test
    void send_allReceiversInvalid_throws3003() {
        MessageSendCmd cmd = directCmd();
        cmd.setReceiverIds(List.of(999L));
        assertThatThrownBy(() -> service.send(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3003);
    }

    @Test
    void send_unknownChannel_throws3040() {
        MessageSendCmd cmd = directCmd();
        cmd.setChannel("pigeon");
        assertThatThrownBy(() -> service.send(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3040);
    }

    @Test
    void send_sms_dispatchesToChannel_noInboxRows() {
        when(smsChannel.channel()).thenReturn(MessageChannelEnum.SMS);
        MessageSendCmd cmd = directCmd();
        cmd.setChannel("sms");
        service.send(cmd);
        verify(smsChannel).dispatch(any(MsgMessage.class), any());
        verify(userMessageMapper, never()).insert(any(MsgUserMessage.class));
    }

    // ---------- 用户消息 ----------

    @Test
    void pageByUser_empty() {
        Page<MsgUserMessage> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(userMessageMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        PageResult<MessageDTO> result = service.pageByUser(1L, new MessagePageQuery());
        assertThat(result.getTotal()).isZero();
        assertThat(result.getList()).isEmpty();
    }

    @Test
    void pageByUser_composesDTO() {
        MsgUserMessage row = new MsgUserMessage();
        row.setId(10L);
        row.setMessageId(100L);
        row.setUserId(1L);
        row.setReadStatus(0);
        Page<MsgUserMessage> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(userMessageMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        MsgMessage message = new MsgMessage();
        message.setId(100L);
        message.setTitle("标题");
        message.setMsgType(1);
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(message));
        when(messageMapper.selectByIds(anyCollection())).thenReturn(List.of(message));

        PageResult<MessageDTO> result = service.pageByUser(1L, new MessagePageQuery());
        assertThat(result.getList()).hasSize(1);
        MessageDTO dto = result.getList().get(0);
        assertThat(dto.getUserMessageId()).isEqualTo(10L);
        assertThat(dto.getTitle()).isEqualTo("标题");
    }

    @Test
    void markRead_notFound_throws3002() {
        when(userMessageMapper.selectById(10L)).thenReturn(null);
        assertThatThrownBy(() -> service.markRead(10L, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3002);
    }

    @Test
    void markRead_otherUser_throws3002() {
        MsgUserMessage row = new MsgUserMessage();
        row.setId(10L);
        row.setUserId(2L);
        row.setReadStatus(0);
        when(userMessageMapper.selectById(10L)).thenReturn(row);
        assertThatThrownBy(() -> service.markRead(10L, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3002);
    }

    @Test
    void markRead_alreadyRead_noUpdate() {
        MsgUserMessage row = new MsgUserMessage();
        row.setId(10L);
        row.setUserId(1L);
        row.setReadStatus(1);
        when(userMessageMapper.selectById(10L)).thenReturn(row);
        service.markRead(10L, 1L);
        verify(userMessageMapper, never()).updateById(any(MsgUserMessage.class));
    }

    @Test
    void markRead_success() {
        MsgUserMessage row = new MsgUserMessage();
        row.setId(10L);
        row.setUserId(1L);
        row.setReadStatus(0);
        when(userMessageMapper.selectById(10L)).thenReturn(row);
        service.markRead(10L, 1L);
        ArgumentCaptor<MsgUserMessage> captor = ArgumentCaptor.forClass(MsgUserMessage.class);
        verify(userMessageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getReadStatus()).isEqualTo(1);
        assertThat(captor.getValue().getReadTime()).isNotNull();
    }

    @Test
    void markAllRead_returnsCount() {
        when(userMessageMapper.update(any(MsgUserMessage.class), any(Wrapper.class))).thenReturn(3);
        assertThat(service.markAllRead(1L)).isEqualTo(3);
    }

    @Test
    void countUnread_returnsCount() {
        when(userMessageMapper.selectCount(any(Wrapper.class))).thenReturn(7L);
        assertThat(service.countUnread(1L)).isEqualTo(7L);
    }

    // ---------- 后台管理 ----------

    @Test
    void pageAll_withReceiverCount() {
        MsgMessage message = new MsgMessage();
        message.setId(100L);
        message.setTitle("t");
        Page<MsgMessage> page = new Page<>(1, 10);
        page.setRecords(List.of(message));
        page.setTotal(1);
        when(messageMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(userMessageMapper.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(Map.of("messageId", 100L, "cnt", 2L)));
        PageResult<MessageManageVO> result = service.pageAll(new MessageManageQuery());
        assertThat(result.getList().get(0).getReceiverCount()).isEqualTo(2L);
    }

    @Test
    void pageAll_empty() {
        Page<MsgMessage> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(messageMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        assertThat(service.pageAll(new MessageManageQuery()).getList()).isEmpty();
    }

    @Test
    void getManage_notFound_throws3001() {
        when(messageMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.getManage(1L))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3001);
    }

    @Test
    void getManage_ok() {
        MsgMessage message = new MsgMessage();
        message.setId(1L);
        message.setTitle("t");
        when(messageMapper.selectById(1L)).thenReturn(message);
        when(userMessageMapper.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(Map.of("messageId", 1L, "cnt", 5L)));
        assertThat(service.getManage(1L).getReceiverCount()).isEqualTo(5L);
    }
}

package com.pivotos.message.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.message.api.dto.MessageSendCmd;
import com.pivotos.message.domain.dto.MessageManageQuery;
import com.pivotos.message.domain.dto.MessageSendRequest;
import com.pivotos.message.domain.vo.MessageManageVO;
import com.pivotos.message.service.MessageService;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.web.annotation.RepeatSubmit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 消息管理（后台发送 + 全量分页） */
@RestController
@RequestMapping("/message/manage")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/page")
    @SaCheckPermission(value = "message:message:list", type = StpSysUtil.TYPE)
    public R<PageResult<MessageManageVO>> page(MessageManageQuery query) {
        return R.ok(messageService.pageAll(query));
    }

    @GetMapping("/{id}")
    @SaCheckPermission(value = "message:message:query", type = StpSysUtil.TYPE)
    public R<MessageManageVO> get(@PathVariable Long id) {
        return R.ok(messageService.getManage(id));
    }

    @PostMapping("/send")
    @RepeatSubmit
    @SaCheckPermission(value = "message:message:send", type = StpSysUtil.TYPE)
    public R<Long> send(@Validated @RequestBody MessageSendRequest request) {
        MessageSendCmd cmd = new MessageSendCmd();
        BeanUtils.copyProperties(request, cmd);
        return R.ok(messageService.send(cmd));
    }
}

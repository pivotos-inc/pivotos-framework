package com.pivotos.message.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.api.enums.MessageErrorCode;
import com.pivotos.message.constant.MessageConstants;
import com.pivotos.message.convert.TemplateConvert;
import com.pivotos.message.domain.dto.TemplateQuery;
import com.pivotos.message.domain.dto.TemplateSaveRequest;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.vo.TemplateVO;
import com.pivotos.message.mapper.MsgTemplateMapper;
import com.pivotos.message.service.TemplateService;
import com.pivotos.message.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/** 消息模板服务实现 */
@Service
@RequiredArgsConstructor
public class TemplateServiceImpl extends ServiceImpl<MsgTemplateMapper, MsgTemplate> implements TemplateService {

    private final TemplateConvert templateConvert;

    @Override
    public PageResult<TemplateVO> pageTemplates(TemplateQuery query) {
        Page<MsgTemplate> page = page(PageUtils.toMpPage(query), Wrappers.<MsgTemplate>lambdaQuery()
                .like(StringUtils.hasText(query.getTemplateName()), MsgTemplate::getTemplateName, query.getTemplateName())
                .like(StringUtils.hasText(query.getTemplateCode()), MsgTemplate::getTemplateCode, query.getTemplateCode())
                .eq(query.getStatus() != null, MsgTemplate::getStatus, query.getStatus())
                .orderByAsc(MsgTemplate::getId));
        return PageUtils.toPageResult(page, templateConvert.toVoList(page.getRecords()));
    }

    @Override
    public TemplateVO getTemplate(Long id) {
        return templateConvert.toVo(requireTemplate(id));
    }

    @Override
    public Long createTemplate(TemplateSaveRequest request) {
        checkCodeUnique(request.getTemplateCode(), null);
        MsgTemplate entity = templateConvert.toEntity(request);
        entity.setId(null);
        if (entity.getMsgType() == null) {
            entity.setMsgType(MessageConstants.TYPE_NOTICE);
        }
        if (!StringUtils.hasText(entity.getChannel())) {
            entity.setChannel("inbox");
        }
        if (entity.getStatus() == null) {
            entity.setStatus(MessageConstants.STATUS_NORMAL);
        }
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateTemplate(TemplateSaveRequest request) {
        requireTemplate(request.getId());
        checkCodeUnique(request.getTemplateCode(), request.getId());
        updateById(templateConvert.toEntity(request));
    }

    @Override
    public void deleteTemplate(Long id) {
        requireTemplate(id);
        removeById(id);
    }

    @Override
    public MsgTemplate requireEnabledByCode(String templateCode) {
        MsgTemplate template = getOne(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateCode, templateCode));
        if (template == null) {
            throw new ServiceException(MessageErrorCode.TEMPLATE_NOT_FOUND);
        }
        if (MessageConstants.STATUS_DISABLED == template.getStatus()) {
            throw new ServiceException(MessageErrorCode.TEMPLATE_DISABLED);
        }
        return template;
    }

    @Override
    public String render(String tpl, Map<String, Object> params) {
        if (tpl == null || params == null || params.isEmpty()) {
            return tpl;
        }
        String result = tpl;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private MsgTemplate requireTemplate(Long id) {
        MsgTemplate template = id == null ? null : getById(id);
        if (template == null) {
            throw new ServiceException(MessageErrorCode.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    private void checkCodeUnique(String templateCode, Long excludeId) {
        long count = count(Wrappers.<MsgTemplate>lambdaQuery()
                .eq(MsgTemplate::getTemplateCode, templateCode)
                .ne(excludeId != null, MsgTemplate::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(MessageErrorCode.TEMPLATE_CODE_EXISTS);
        }
    }
}

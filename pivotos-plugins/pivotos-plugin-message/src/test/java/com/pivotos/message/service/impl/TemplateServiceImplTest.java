package com.pivotos.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.convert.TemplateConvertImpl;
import com.pivotos.message.domain.dto.TemplateQuery;
import com.pivotos.message.domain.dto.TemplateSaveRequest;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.vo.TemplateVO;
import com.pivotos.message.mapper.MsgTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** TemplateServiceImpl 单元测试（Mapper 全 Mock） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TemplateServiceImplTest {

    @Mock
    private MsgTemplateMapper templateMapper;

    private TemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TemplateServiceImpl(new TemplateConvertImpl());
        ReflectionTestUtils.setField(service, "baseMapper", templateMapper);
    }

    private MsgTemplate template(Long id, String code, int status) {
        MsgTemplate t = new MsgTemplate();
        t.setId(id);
        t.setTemplateCode(code);
        t.setTemplateName("模板" + code);
        t.setTitleTpl("标题 {name}");
        t.setContentTpl("内容 {name} 你好");
        t.setMsgType(1);
        t.setChannel("inbox");
        t.setStatus(status);
        return t;
    }

    // ---------- render ----------

    @Test
    void render_replacesPlaceholders() {
        String result = service.render("你好 {name}，工单 {no} 已处理", Map.of("name", "张三", "no", 1001));
        assertThat(result).isEqualTo("你好 张三，工单 1001 已处理");
    }

    @Test
    void render_missingParam_keepsPlaceholder() {
        assertThat(service.render("你好 {name}", Map.of())).isEqualTo("你好 {name}");
        assertThat(service.render("你好 {name}", null)).isEqualTo("你好 {name}");
        assertThat(service.render(null, Map.of("name", "x"))).isNull();
    }

    // ---------- requireEnabledByCode ----------

    @Test
    void requireEnabledByCode_notFound_throws3020() {
        when(templateMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.requireEnabledByCode("missing"))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3020);
    }

    @Test
    void requireEnabledByCode_disabled_throws3022() {
        when(templateMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(template(1L, "t1", 1));
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(template(1L, "t1", 1)));
        assertThatThrownBy(() -> service.requireEnabledByCode("t1"))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3022);
    }

    @Test
    void requireEnabledByCode_ok() {
        MsgTemplate t = template(1L, "t1", 0);
        when(templateMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(t);
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        assertThat(service.requireEnabledByCode("t1").getTemplateCode()).isEqualTo("t1");
    }

    // ---------- CRUD ----------

    @Test
    void createTemplate_duplicate_throws3021() {
        when(templateMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        TemplateSaveRequest req = request("dup");
        assertThatThrownBy(() -> service.createTemplate(req))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3021);
    }

    @Test
    void createTemplate_defaultsApplied() {
        when(templateMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, MsgTemplate.class).setId(99L);
            return 1;
        }).when(templateMapper).insert(any(MsgTemplate.class));
        Long id = service.createTemplate(request("new_tpl"));
        assertThat(id).isEqualTo(99L);
    }

    @Test
    void updateTemplate_notFound_throws3020() {
        when(templateMapper.selectById(5L)).thenReturn(null);
        TemplateSaveRequest req = request("x");
        req.setId(5L);
        assertThatThrownBy(() -> service.updateTemplate(req))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(3020);
    }

    @Test
    void updateTemplate_success() {
        when(templateMapper.selectById(5L)).thenReturn(template(5L, "x", 0));
        when(templateMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(templateMapper.updateById(any(MsgTemplate.class))).thenReturn(1);
        TemplateSaveRequest req = request("x2");
        req.setId(5L);
        service.updateTemplate(req);
    }

    @Test
    void deleteTemplate_notFound_throws3020() {
        when(templateMapper.selectById(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.deleteTemplate(5L))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void deleteTemplate_success() {
        when(templateMapper.selectById(5L)).thenReturn(template(5L, "x", 0));
        when(templateMapper.deleteById(5L)).thenReturn(1);
        service.deleteTemplate(5L);
    }

    @Test
    void getTemplate_ok() {
        when(templateMapper.selectById(5L)).thenReturn(template(5L, "x", 0));
        TemplateVO vo = service.getTemplate(5L);
        assertThat(vo.getTemplateCode()).isEqualTo("x");
    }

    @Test
    void pageTemplates_returnsPage() {
        Page<MsgTemplate> page = new Page<>(1, 10);
        page.setRecords(List.of(template(1L, "a", 0), template(2L, "b", 0)));
        page.setTotal(2);
        when(templateMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        PageResult<TemplateVO> result = service.pageTemplates(new TemplateQuery());
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getList()).hasSize(2);
    }

    private TemplateSaveRequest request(String code) {
        TemplateSaveRequest req = new TemplateSaveRequest();
        req.setTemplateCode(code);
        req.setTemplateName("模板");
        req.setTitleTpl("t");
        req.setContentTpl("c");
        return req;
    }
}

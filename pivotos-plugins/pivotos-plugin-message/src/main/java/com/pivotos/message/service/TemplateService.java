package com.pivotos.message.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.message.domain.dto.TemplateQuery;
import com.pivotos.message.domain.dto.TemplateSaveRequest;
import com.pivotos.message.domain.entity.MsgTemplate;
import com.pivotos.message.domain.vo.TemplateVO;

import java.util.Map;

/** 消息模板服务 */
public interface TemplateService {

    /** 分页查询模板 */
    PageResult<TemplateVO> pageTemplates(TemplateQuery query);

    /** 按 ID 查询模板 */
    TemplateVO getTemplate(Long id);

    /** 新增模板，返回模板 ID */
    Long createTemplate(TemplateSaveRequest request);

    /** 更新模板 */
    void updateTemplate(TemplateSaveRequest request);

    /** 删除模板 */
    void deleteTemplate(Long id);

    /**
     * 按编码取正常状态模板（发送链路用）
     *
     * @throws com.pivotos.common.core.exception.ServiceException 模板不存在/已停用
     */
    MsgTemplate requireEnabledByCode(String templateCode);

    /** 渲染模板文本：占位符 {var} 由 params 填充，缺省参数保留原占位符 */
    String render(String tpl, Map<String, Object> params);
}

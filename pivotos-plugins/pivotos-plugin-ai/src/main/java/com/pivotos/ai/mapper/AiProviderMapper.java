package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 供应商 Mapper。
 * 管理侧 CRUD 走 BaseMapper 标准方法（租户行级过滤自动生效）；
 * 对话解析链需要「租户自有优先 → 平台（tenant_id=0）兜底」的跨租户显式查询，
 * 用 @InterceptorIgnore 绕过租户拦截器并手写 tenant_id 条件。
 */
@Mapper
public interface AiProviderMapper extends BaseMapper<AiProvider> {

    /** 指定租户的启用供应商（sort 正序），解析链专用 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ai_provider WHERE tenant_id = #{tenantId} AND status = 0 AND deleted = 0 "
            + "ORDER BY sort ASC, id ASC")
    List<AiProvider> selectActiveByTenant(@Param("tenantId") Long tenantId);

    /** 按 id 跨租户取供应商（归属校验由 service 层完成），解析链专用 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ai_provider WHERE id = #{id} AND deleted = 0")
    AiProvider selectByIdAnyTenant(@Param("id") Long id);
}

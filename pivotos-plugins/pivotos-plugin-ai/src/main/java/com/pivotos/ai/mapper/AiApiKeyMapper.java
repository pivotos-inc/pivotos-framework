package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.starter.mybatis.crypto.FieldEncryptTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI API Key Mapper。
 * 管理侧 CRUD 走 BaseMapper 标准方法（租户行级过滤自动生效）；
 * 调用链取 Key 与健康度更新须跨租户执行（租户可使用平台兜底 Key，
 * 流式回调线程亦无租户上下文），用 @InterceptorIgnore 绕过租户拦截器。
 */
@Mapper
public interface AiApiKeyMapper extends BaseMapper<AiApiKey> {

    /**
     * 供应商启用中的 Key（id 正序，轮询顺序稳定），调用链专用。
     * 自定义 SQL 不走 autoResultMap，api_key 解密须显式挂 TypeHandler。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Results({@Result(column = "api_key", property = "apiKey", typeHandler = FieldEncryptTypeHandler.class)})
    @Select("SELECT * FROM ai_api_key WHERE provider_id = #{providerId} AND status = 0 AND deleted = 0 "
            + "ORDER BY id ASC")
    List<AiApiKey> selectActiveByProvider(@Param("providerId") Long providerId);

    /** 连续失败计数原子 +1，返回累加后的值需另查（并发安全由行锁保证） */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ai_api_key SET fail_count = fail_count + 1 WHERE id = #{id} AND deleted = 0")
    int incrementFailCount(@Param("id") Long id);

    /** 成功清零失败计数（fail_count>0 才更新，避免每次成功都写库） */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ai_api_key SET fail_count = 0 WHERE id = #{id} AND fail_count > 0 AND deleted = 0")
    int resetFailCount(@Param("id") Long id);

    /**
     * 达阈值原子停用：仅 status=0 且 fail_count>=threshold 时置停用。
     * 返回受影响行数，>0 表示本次完成停用（并发下只会成功一次，据此发告警防重复）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ai_api_key SET status = 1 WHERE id = #{id} AND status = 0 "
            + "AND fail_count >= #{threshold} AND deleted = 0")
    int disableIfFailExceeded(@Param("id") Long id, @Param("threshold") int threshold);

    /** 跨租户读单条 Key（告警与阈值判断用，api_key 不解密以免明文外带） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, provider_id, label, status, fail_count, tenant_id, create_by "
            + "FROM ai_api_key WHERE id = #{id} AND deleted = 0")
    AiApiKey selectBriefById(@Param("id") Long id);
}

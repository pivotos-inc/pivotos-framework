package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiUsage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI Token 用量 Mapper（S92）。
 * 写入来自 Reactor/虚拟线程回调（无租户上下文），聚合面向平台管理员跨租户视角，
 * 故读写均 @InterceptorIgnore 绕过租户行级过滤（同 AiApiKeyMapper 调用链口径）。
 */
@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    /** 显式全字段插入（回调线程无审计上下文，ID/时间由记录器预填） */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO ai_usage (id, user_id, tenant_id, provider_id, provider_code, key_id, "
            + "model, scene, call_type, prompt_tokens, completion_tokens, total_tokens, failed, "
            + "create_by, create_time, update_by, update_time, deleted) "
            + "VALUES (#{id}, #{userId}, #{tenantId}, #{providerId}, #{providerCode}, #{keyId}, "
            + "#{model}, #{scene}, #{callType}, #{promptTokens}, #{completionTokens}, #{totalTokens}, #{failed}, "
            + "#{createBy}, #{createTime}, #{updateBy}, #{updateTime}, 0)")
    int insertUsage(AiUsage usage);

    /** 总量汇总（调用数/失败数/三类 token 合计） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) AS calls, COALESCE(SUM(failed), 0) AS failedCalls, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM ai_usage WHERE deleted = 0 AND create_time >= #{start}")
    Map<String, Object> selectSummary(@Param("start") LocalDateTime start);

    /** 按场景聚合 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT scene, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM ai_usage WHERE deleted = 0 AND create_time >= #{start} GROUP BY scene")
    List<Map<String, Object>> selectByScene(@Param("start") LocalDateTime start);

    /** 按供应商 × Key 聚合（providerCode/keyId 可能为 null：静态兜底） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT provider_code AS providerCode, key_id AS keyId, COUNT(*) AS calls, "
            + "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, "
            + "COALESCE(SUM(completion_tokens), 0) AS completionTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM ai_usage WHERE deleted = 0 AND create_time >= #{start} "
            + "GROUP BY provider_code, key_id ORDER BY totalTokens DESC")
    List<Map<String, Object>> selectByProvider(@Param("start") LocalDateTime start);

    /** 按用户聚合（user_id 可能为 null：未登录链路） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT user_id AS userId, COUNT(*) AS calls, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM ai_usage WHERE deleted = 0 AND create_time >= #{start} "
            + "GROUP BY user_id ORDER BY totalTokens DESC")
    List<Map<String, Object>> selectByUser(@Param("start") LocalDateTime start);

    /** 按日趋势（缺日由服务层补 0） */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS day, COUNT(*) AS calls, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens "
            + "FROM ai_usage WHERE deleted = 0 AND create_time >= #{start} "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d') ORDER BY day")
    List<Map<String, Object>> selectTrend(@Param("start") LocalDateTime start);

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * 两个「为什么」：
     * <ol>
     *   <li><b>为什么显式 DELETE</b>：{@code TenantBaseDO → BaseDO#deleted} 带 {@code @TableLogic}，
     *       {@code BaseMapper#delete*} 只置 {@code deleted=1}，空间不回收。</li>
     *   <li><b>为什么加 @InterceptorIgnore</b>：与本 Mapper 其余方法同口径——
     *       ai_usage 的写入来自 Reactor / 虚拟线程回调（无租户上下文），聚合也是平台跨租户视角，
     *       故读写一律绕过租户行级过滤。留存清理自然也是<strong>跨租户全局清理</strong>，
     *       对应调用方走 {@code RetentionCleanupTemplate#runGlobal}。
     *       若这里不显式声明，绑定租户上下文时会被追加 {@code tenant_id = ?}，
     *       与本表既定口径冲突（部分行 tenant_id 为 null，会被漏掉）。</li>
     * </ol>
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("<script>DELETE FROM ai_usage WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
